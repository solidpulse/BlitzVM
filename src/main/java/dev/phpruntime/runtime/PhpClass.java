package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-request runtime representation of a PHP class or interface.
 * Holds resolved inheritance links, method table, static property slots, and
 * evaluated class constants for a single {@link PhpContext}.
 */
public final class PhpClass {

    public record MethodEntry(String name, PhpClass declaringClass, Visibility visibility,
                              boolean isStatic, boolean isFinal, RootCallTarget callTarget) {
    }

    public static final class StaticSlot {
        public final String name;
        public final Visibility visibility;
        public final PhpClass declaringClass;
        public Object value;

        public StaticSlot(String name, Visibility visibility, PhpClass declaringClass, Object value) {
            this.name = name;
            this.visibility = visibility;
            this.declaringClass = declaringClass;
            this.value = value;
        }
    }

    public record InstanceProp(String name, String storageKey, Visibility visibility,
                               PhpClass declaringClass, Object constDefault, RootCallTarget defaultTarget) {
    }

    public record PropertyResolution(String storageKey, boolean accessible, Visibility visibility, String declaringClass) {
    }

    private final PhpClassTemplate template;
    private final long hierarchyId;
    private final PhpClass parent;
    private final List<PhpClass> interfaces;
    private final Map<String, MethodEntry> methods = new HashMap<>();
    private final MethodEntry[] methodsById;
    private final Map<String, StaticSlot> staticProperties = new HashMap<>();
    private final Map<String, PhpClassTemplate.ConstantDecl> constantDecls = new LinkedHashMap<>();
    private final Map<String, Object> constants = new HashMap<>();
    private final List<InstanceProp> instanceProps = new ArrayList<>();
    private final Map<String, InstanceProp> visibleProps = new HashMap<>();
    private final Map<String, InstanceProp> privateProps = new HashMap<>();
    private final Set<String> declaredPropNames = new HashSet<>();

    private String[] defaultPropKeys;
    private Object[] defaultPropValues;
    private MethodEntry constructMethod;
    private MethodEntry getMethod;
    private MethodEntry setMethod;
    private MethodEntry issetMethod;
    private MethodEntry unsetMethod;
    private MethodEntry callMethod;
    private MethodEntry callStaticMethod;
    private MethodEntry toStringMethod;
    private MethodEntry cloneMethod;

    private final boolean isThrowable;

    public PhpClass(PhpLanguage language, PhpClassTemplate template, PhpClass parent, List<PhpClass> interfaces) {
        this.template = template;
        this.parent = parent;
        this.interfaces = List.copyOf(interfaces);
        this.isThrowable = (parent != null && parent.isThrowable)
                || "exception".equals(template.lowerName())
                || "error".equals(template.lowerName());

        List<PhpClassTemplate> chain = new ArrayList<>();
        for (PhpClass c = this; c != null; c = c.parent) {
            chain.add(c.template);
        }
        this.hierarchyId = language.hierarchyId(chain);

        // Inherit methods from parent, then overlay this class's declared methods.
        int maxMethodId = parent != null ? parent.methodsById.length : 0;
        for (PhpClassTemplate.MethodDecl m : template.methods().values()) {
            if (m.methodId() >= maxMethodId) {
                maxMethodId = m.methodId() + 1;
            }
        }
        this.methodsById = new MethodEntry[maxMethodId];
        if (parent != null) {
            this.methods.putAll(parent.methods);
            System.arraycopy(parent.methodsById, 0, this.methodsById, 0, parent.methodsById.length);
        }
        for (PhpClassTemplate.MethodDecl m : template.methods().values()) {
            MethodEntry entry = new MethodEntry(m.name(), this, m.visibility(), m.isStatic(), m.isFinal(), m.callTarget());
            this.methods.put(m.lowerName(), entry);
            this.methodsById[m.methodId()] = entry;
        }

        // Cache magic methods if present anywhere in hierarchy.
        this.constructMethod = methods.get("__construct");
        this.getMethod = methods.get("__get");
        this.setMethod = methods.get("__set");
        this.issetMethod = methods.get("__isset");
        this.unsetMethod = methods.get("__unset");
        this.callMethod = methods.get("__call");
        this.callStaticMethod = methods.get("__callstatic");
        this.toStringMethod = methods.get("__tostring");
        this.cloneMethod = methods.get("__clone");

        // Inherit instance & static property descriptors from parent.
        if (parent != null) {
            this.staticProperties.putAll(parent.staticProperties);
            this.instanceProps.addAll(parent.instanceProps);
            this.visibleProps.putAll(parent.visibleProps);
            this.privateProps.putAll(parent.privateProps);
            this.declaredPropNames.addAll(parent.declaredPropNames);
        }

        // Apply own property declarations (overriding non-private properties in place).
        for (PhpClassTemplate.PropertyDecl p : template.properties()) {
            this.declaredPropNames.add(p.name());
            if (p.isStatic()) {
                this.staticProperties.put(p.name(), new StaticSlot(p.name(), p.visibility(), this, PhpNull.INSTANCE));
            } else if (p.visibility() == Visibility.PRIVATE) {
                String storageKey = "\0" + template.name() + "\0" + p.name();
                InstanceProp ip = new InstanceProp(p.name(), storageKey, Visibility.PRIVATE, this, p.constDefault(), p.defaultTarget());
                this.instanceProps.add(ip);
                this.privateProps.put(template.lowerName() + "\0" + p.name(), ip);
            } else {
                InstanceProp ip = new InstanceProp(p.name(), p.name(), p.visibility(), this, p.constDefault(), p.defaultTarget());
                InstanceProp prev = this.visibleProps.put(p.name(), ip);
                if (prev != null) {
                    int idx = this.instanceProps.indexOf(prev);
                    if (idx >= 0) {
                        this.instanceProps.set(idx, ip);
                    } else {
                        this.instanceProps.add(ip);
                    }
                } else {
                    this.instanceProps.add(ip);
                }
            }
        }

        // Record constant declarations (evaluated lazily on first lookup or initialization).
        for (PhpClassTemplate.ConstantDecl c : template.constants()) {
            this.constantDecls.put(c.name(), c);
        }
    }

    /** Evaluates static property default expressions after the class is registered in PhpContext. */
    @TruffleBoundary
    public void initStaticProperties() {
        for (PhpClassTemplate.PropertyDecl p : template.properties()) {
            if (p.isStatic()) {
                Object initVal = evalExpr(p.constDefault(), p.defaultTarget());
                this.staticProperties.get(p.name()).value = PhpOps.copyOnAssign(initVal);
            }
        }
    }

    public PhpClassTemplate template() {
        return template;
    }

    public long hierarchyId() {
        return hierarchyId;
    }

    public String name() {
        return template.name();
    }

    public PhpClass parent() {
        return parent;
    }

    public List<PhpClass> interfaces() {
        return interfaces;
    }

    public boolean isInterface() {
        return template.isInterface();
    }

    public boolean isAbstract() {
        return template.isAbstract();
    }

    public boolean isFinal() {
        return template.isFinal();
    }

    public boolean isThrowable() {
        return isThrowable;
    }

    public MethodEntry methodById(int id) {
        return id < methodsById.length ? methodsById[id] : null;
    }

    @TruffleBoundary
    public MethodEntry lookupMethod(String name) {
        return methods.get(name.toLowerCase());
    }

    public boolean hasMethod(String name) {
        return lookupMethod(name) != null;
    }

    public MethodEntry constructMethod() {
        return constructMethod;
    }

    public MethodEntry getMethod() {
        return getMethod;
    }

    public MethodEntry setMethod() {
        return setMethod;
    }

    public MethodEntry issetMethod() {
        return issetMethod;
    }

    public MethodEntry unsetMethod() {
        return unsetMethod;
    }

    public MethodEntry callMethod() {
        return callMethod;
    }

    public MethodEntry callStaticMethod() {
        return callStaticMethod;
    }

    public MethodEntry toStringMethod() {
        return toStringMethod;
    }

    public MethodEntry cloneMethod() {
        return cloneMethod;
    }

    public boolean hasMagicPropertyAccess() {
        return getMethod != null || setMethod != null || issetMethod != null || unsetMethod != null;
    }

    @TruffleBoundary
    public boolean isInstanceOf(PhpClass target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        for (PhpClass iface : interfaces) {
            if (iface.isInstanceOf(target)) {
                return true;
            }
        }
        return parent != null && parent.isInstanceOf(target);
    }

    @TruffleBoundary
    public StaticSlot lookupStaticProperty(String name) {
        return staticProperties.get(name);
    }

    @TruffleBoundary
    public boolean hasDeclaredProperty(String name) {
        return declaredPropNames.contains(name);
    }

    @TruffleBoundary
    public InstanceProp findVisibleProp(String name) {
        return visibleProps.get(name);
    }

    @TruffleBoundary
    public Object lookupConstant(String name) {
        if (name.equalsIgnoreCase("class")) {
            return template.name();
        }
        Object cached = constants.get(name);
        if (cached != null) {
            return cached;
        }
        PhpClassTemplate.ConstantDecl own = constantDecls.get(name);
        if (own != null) {
            Object val = evalExpr(own.constValue(), own.valueTarget());
            constants.put(name, val);
            return val;
        }
        if (parent != null) {
            Object p = parent.lookupConstantOrNull(name);
            if (p != null) {
                return p;
            }
        }
        for (PhpClass iface : interfaces) {
            Object iv = iface.lookupConstantOrNull(name);
            if (iv != null) {
                return iv;
            }
        }
        return null;
    }

    private Object lookupConstantOrNull(String name) {
        return lookupConstant(name);
    }

    @TruffleBoundary
    public PropertyResolution resolveProperty(PhpContext ctx, String name, String callingClassLower) {
        if (callingClassLower != null) {
            InstanceProp priv = privateProps.get(callingClassLower + "\0" + name);
            if (priv != null) {
                return new PropertyResolution(priv.storageKey(), true, Visibility.PRIVATE, priv.declaringClass().name());
            }
        }
        InstanceProp vis = visibleProps.get(name);
        if (vis != null) {
            boolean accessible = vis.visibility() == Visibility.PUBLIC
                    || isProtectedAccessible(ctx, vis.declaringClass(), callingClassLower);
            return new PropertyResolution(vis.storageKey(), accessible, vis.visibility(), vis.declaringClass().name());
        }
        InstanceProp ownPriv = privateProps.get(template.lowerName() + "\0" + name);
        if (ownPriv != null) {
            return new PropertyResolution(ownPriv.storageKey(), false, Visibility.PRIVATE, ownPriv.declaringClass().name());
        }
        return new PropertyResolution(name, true, Visibility.PUBLIC, null);
    }

    private boolean isProtectedAccessible(PhpContext ctx, PhpClass declaring, String callingClassLower) {
        if (callingClassLower == null) {
            return false;
        }
        PhpClass caller = ctx.lookupClass(callingClassLower);
        return caller != null && (this.isInstanceOf(caller) || caller.isInstanceOf(declaring));
    }

    @TruffleBoundary
    public void populateDefaults(PhpObject obj) {
        if (instanceProps.isEmpty()) {
            return;
        }
        if (defaultPropKeys == null) {
            String[] keys = new String[instanceProps.size()];
            Object[] vals = new Object[instanceProps.size()];
            for (int i = 0; i < instanceProps.size(); i++) {
                InstanceProp p = instanceProps.get(i);
                keys[i] = p.storageKey();
                vals[i] = evalExpr(p.constDefault(), p.defaultTarget());
            }
            this.defaultPropKeys = keys;
            this.defaultPropValues = vals;
        }
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        for (int i = 0; i < defaultPropKeys.length; i++) {
            put.execute(obj, defaultPropKeys[i], PhpOps.copyOnAssign(defaultPropValues[i]));
        }
    }

    private static Object evalExpr(Object constVal, RootCallTarget target) {
        if (target == null) {
            return constVal;
        }
        return target.call();
    }

    /** Collects visible property entries for an object from {@code callerClassName} scope. */
    @TruffleBoundary
    public static List<Map.Entry<Object, Object>> visibleEntries(PhpContext ctx, PhpObject obj, String callerClassName) {
        String callerLower = callerClassName == null ? null : callerClassName.toLowerCase();
        PhpClass caller = callerLower == null ? null : ctx.lookupClass(callerLower);
        PhpClass cls = obj.phpClass();
        Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        List<Map.Entry<Object, Object>> out = new ArrayList<>(keys.length);
        for (Object rawKey : keys) {
            String k = (String) rawKey;
            if (k.startsWith("\0")) {
                int sep = k.indexOf('\0', 1);
                String declaring = k.substring(1, sep);
                String prop = k.substring(sep + 1);
                if (callerLower != null && declaring.equalsIgnoreCase(callerLower)) {
                    Object pv = get.execute(obj, k, PhpNull.INSTANCE);
                    if (pv instanceof PhpBacktrace.LazyTrace lt) {
                        pv = lt.toPhpArray();
                    }
                    out.add(Map.entry(prop, pv));
                }
                continue;
            }
            InstanceProp p = cls.findVisibleProp(k);
            if (p != null && p.visibility() == Visibility.PROTECTED) {
                if (caller != null && (cls.isInstanceOf(caller) || caller.isInstanceOf(p.declaringClass()))) {
                    Object pv = get.execute(obj, k, PhpNull.INSTANCE);
                    if (pv instanceof PhpBacktrace.LazyTrace lt) {
                        pv = lt.toPhpArray();
                    }
                    out.add(Map.entry(k, pv));
                }
                continue;
            }
            Object pv = get.execute(obj, k, PhpNull.INSTANCE);
            if (pv instanceof PhpBacktrace.LazyTrace lt) {
                pv = lt.toPhpArray();
            }
            out.add(Map.entry(k, pv));
        }
        return out;
    }
}
