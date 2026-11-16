package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpExprRootNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-request runtime representation of a PHP class, interface, or trait.
 * Holds resolved inheritance links, composed traits, method table, static property
 * slots, and evaluated class constants for a single {@link PhpContext}.
 */
public final class PhpClass {

    public record MethodEntry(String name, PhpClass declaringClass, Visibility visibility,
                              boolean isStatic, boolean isFinal, boolean fromTrait,
                              RootCallTarget callTarget) {
    }

    public static final class StaticSlot {
        public final String name;
        public final Visibility visibility;
        public final PhpClass declaringClass;
        private final Object constDefault;
        private final RootCallTarget defaultTarget;
        private boolean initialized;
        private Object value;

        public StaticSlot(String name, Visibility visibility, PhpClass declaringClass,
                          Object constDefault, RootCallTarget defaultTarget) {
            this.name = name;
            this.visibility = visibility;
            this.declaringClass = declaringClass;
            this.constDefault = constDefault;
            this.defaultTarget = defaultTarget;
            this.initialized = defaultTarget == null;
            this.value = defaultTarget == null ? PhpOps.copyOnAssign(constDefault) : null;
        }

        /** The stored value, possibly a {@link PhpReference} cell; runs the initializer on first use. */
        private Object raw() {
            if (!initialized) {
                // Set first so a self-referencing initializer sees null instead of recursing;
                // reset on failure so a throwing initializer is retried, as php does.
                initialized = true;
                try {
                    value = PhpOps.copyOnAssign(evalExpr(constDefault, defaultTarget, declaringClass));
                } catch (RuntimeException e) {
                    initialized = false;
                    throw e;
                }
            }
            return value;
        }

        /** The value, with a reference cell bound to this property unwrapped. */
        public Object get() {
            Object v = raw();
            return v instanceof PhpReference ref ? ref.value : v;
        }

        /** Assigns, writing through a reference cell bound to this property. */
        public void set(Object v) {
            if (initialized && value instanceof PhpReference ref) {
                ref.value = v;
                return;
            }
            initialized = true;
            value = v;
        }

        /** The cell for {@code &Cls::$p}, converting the plain value into one on first use. */
        public PhpReference obtainCell() {
            Object v = raw();
            if (v instanceof PhpReference ref) {
                if (ref.value == null) {
                    ref.value = PhpNull.INSTANCE;
                }
                return ref;
            }
            PhpReference ref = new PhpReference(v == null ? PhpNull.INSTANCE : v);
            value = ref;
            return ref;
        }

        /** {@code Cls::$p = &$x}: binds this property to another cell. */
        public void bindCell(PhpReference cell) {
            initialized = true;
            value = cell;
        }
    }

    /** Sentinel stored in {@link PhpObject} for an uninitialized {@code readonly} property. */
    public static final Object UNINITIALIZED = new Object();

    public record InstanceProp(String name, String storageKey, Visibility visibility, boolean isReadonly,
                               PhpClass declaringClass, Object constDefault, RootCallTarget defaultTarget) {
    }

    public record PropertyResolution(String storageKey, boolean accessible, Visibility visibility,
                                     boolean isReadonly, boolean canInitReadonly, String declaringClass) {
    }

    private final PhpClassTemplate template;
    private final long hierarchyId;
    private final PhpClass parent;
    private final List<PhpClass> interfaces;
    private final List<PhpClass> usedTraits;
    private final List<PhpClassTemplate.PropertyDecl> traitProperties;
    private final List<PhpClassTemplate.ConstantDecl> traitConstants;
    private final Map<String, MethodEntry> methods = new HashMap<>();
    private MethodEntry[] methodsById;
    private final Map<String, StaticSlot> staticProperties = new HashMap<>();
    private final Map<String, PhpClassTemplate.ConstantDecl> constantDecls = new LinkedHashMap<>();
    private final Map<String, Object> constants = new HashMap<>();
    private final Set<String> evaluatingConstants = new HashSet<>();
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
    private final int splInterfaces;

    public PhpClass(PhpLanguage language, PhpClassTemplate template, PhpClass parent, List<PhpClass> interfaces) {
        this(language, template, parent, interfaces, Map.of());
    }

    public PhpClass(PhpLanguage language, PhpClassTemplate template, PhpClass parent,
                    List<PhpClass> interfaces, Map<String, PhpClass> resolvedTraits) {
        this.template = template;
        this.parent = parent;
        this.interfaces = List.copyOf(interfaces);
        int interfaceBits = template.isInterface() ? SplInterfaces.declared(template.lowerName()) : 0;
        if (parent != null) {
            interfaceBits |= parent.splInterfaces;
        }
        for (PhpClass iface : interfaces) {
            interfaceBits |= iface.splInterfaces;
        }
        this.splInterfaces = interfaceBits;
        this.isThrowable = (parent != null && parent.isThrowable)
                || "exception".equals(template.lowerName())
                || "error".equals(template.lowerName());

        List<PhpClass> directTraits = new ArrayList<>();
        Set<String> seenTraits = new HashSet<>();
        for (PhpClassTemplate.TraitUse use : template.traitUses()) {
            for (String tn : use.traitNames()) {
                String tl = PhpStrings.lower(tn);
                if (seenTraits.add(tl)) {
                    directTraits.add(resolvedTraits.get(tl));
                }
            }
        }
        this.usedTraits = List.copyOf(directTraits);

        List<PhpClassTemplate> chain = new ArrayList<>();
        for (PhpClass c = this; c != null; c = c.parent) {
            chain.add(c.template);
        }
        this.hierarchyId = language.hierarchyId(chain);

        // Inherit methods from parent, compose traits, then overlay this class's declared methods.
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

        if (!template.traitUses().isEmpty()) {
            composeTraitMethods(language, resolvedTraits);
        }

        boolean ownIsTrait = template.isTrait();
        for (PhpClassTemplate.MethodDecl m : template.methods().values()) {
            MethodEntry entry = new MethodEntry(m.name(), this, m.visibility(), m.isStatic(), m.isFinal(), ownIsTrait, m.callTarget());
            putMethod(m.lowerName(), m.methodId(), entry);
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

        // Collect trait properties (applied before own declarations; own declarations win on duplicates).
        Set<String> ownPropNames = new HashSet<>();
        for (PhpClassTemplate.PropertyDecl p : template.properties()) {
            ownPropNames.add(p.name());
        }
        List<PhpClassTemplate.PropertyDecl> allTraitProps = new ArrayList<>();
        for (PhpClass tr : usedTraits) {
            for (PhpClassTemplate.PropertyDecl tp : tr.traitProperties) {
                allTraitProps.add(tp);
                if (!ownPropNames.contains(tp.name())) {
                    applyPropertyDecl(tp);
                }
            }
        }
        allTraitProps.addAll(template.properties());
        this.traitProperties = ownIsTrait ? List.copyOf(allTraitProps) : List.of();

        // Apply own property declarations (overriding non-private properties in place).
        for (PhpClassTemplate.PropertyDecl p : template.properties()) {
            applyPropertyDecl(p);
        }

        // Record trait & own constant declarations (evaluated lazily on first lookup).
        for (PhpClass tr : usedTraits) {
            for (PhpClassTemplate.ConstantDecl tc : tr.traitConstants) {
                this.constantDecls.putIfAbsent(tc.name(), tc);
            }
        }
        for (PhpClassTemplate.ConstantDecl c : template.constants()) {
            this.constantDecls.put(c.name(), c);
        }
        this.traitConstants = ownIsTrait ? List.copyOf(this.constantDecls.values()) : List.of();
    }

    private void putMethod(String lower, int methodId, MethodEntry entry) {
        this.methods.put(lower, entry);
        if (methodId >= this.methodsById.length) {
            this.methodsById = java.util.Arrays.copyOf(this.methodsById, Math.max(methodId + 1, this.methodsById.length * 2));
        }
        this.methodsById[methodId] = entry;
    }

    private void composeTraitMethods(PhpLanguage language, Map<String, PhpClass> resolvedTraits) {
        Map<String, MethodEntry> composed = new LinkedHashMap<>();
        Map<String, String> sourceTrait = new HashMap<>();

        for (PhpClassTemplate.TraitUse use : template.traitUses()) {
            Set<String> excluded = new HashSet<>();
            for (PhpClassTemplate.TraitPrecedence prec : use.precedences()) {
                String mLower = PhpStrings.lower(prec.methodName());
                for (String ex : prec.excludedTraits()) {
                    excluded.add(PhpStrings.lower(ex) + "\0" + mLower);
                }
            }
            for (String tName : use.traitNames()) {
                String tLower = PhpStrings.lower(tName);
                PhpClass tCls = resolvedTraits.get(tLower);
                for (MethodEntry tm : tCls.methods.values()) {
                    String mLower = PhpStrings.lower(tm.name());
                    if (excluded.contains(tLower + "\0" + mLower)) {
                        continue;
                    }
                    MethodEntry prev = composed.get(mLower);
                    if (prev != null) {
                        if (prev.callTarget() == null && tm.callTarget() != null) {
                            // concrete trait method replaces abstract
                        } else if (tm.callTarget() == null) {
                            continue;
                        } else if (prev.callTarget() == tm.callTarget()) {
                            continue;
                        } else if (!template.methods().containsKey(mLower)) {
                            throw PhpError.fatal("Trait method " + tCls.name() + "::" + tm.name()
                                    + " has not been applied as " + template.name() + "::" + tm.name()
                                    + ", because of collision with " + sourceTrait.get(mLower) + "::" + prev.name());
                        }
                    }
                    composed.put(mLower, new MethodEntry(
                            tm.name(), this, tm.visibility(), tm.isStatic(), tm.isFinal(), true, tm.callTarget()));
                    sourceTrait.put(mLower, tCls.name());
                }
            }
            for (PhpClassTemplate.TraitAlias al : use.aliases()) {
                String mLower = PhpStrings.lower(al.methodName());
                MethodEntry src = findAliasSource(al, use.traitNames(), resolvedTraits, composed);
                if (src == null) {
                    throw PhpError.fatal("An alias was defined for method " + al.methodName() + ", which does not exist");
                }
                Visibility vis = al.visibility() != null ? al.visibility() : src.visibility();
                if (al.aliasName() != null) {
                    String aLower = PhpStrings.lower(al.aliasName());
                    composed.put(aLower, new MethodEntry(
                            al.aliasName(), this, vis, src.isStatic(), src.isFinal(), true, src.callTarget()));
                } else {
                    composed.put(mLower, new MethodEntry(
                            src.name(), this, vis, src.isStatic(), src.isFinal(), true, src.callTarget()));
                }
            }
        }

        for (Map.Entry<String, MethodEntry> e : composed.entrySet()) {
            String lower = e.getKey();
            MethodEntry tm = e.getValue();
            if (tm.callTarget() == null) {
                MethodEntry inherited = this.methods.get(lower);
                if (inherited != null && inherited.callTarget() != null) {
                    continue;
                }
            }
            putMethod(lower, language.methodId(tm.name()), tm);
        }
    }

    private static MethodEntry findAliasSource(PhpClassTemplate.TraitAlias al, List<String> traitNames,
                                             Map<String, PhpClass> resolvedTraits,
                                             Map<String, MethodEntry> composed) {
        String mLower = PhpStrings.lower(al.methodName());
        if (al.traitName() != null) {
            PhpClass tr = resolvedTraits.get(PhpStrings.lower(al.traitName()));
            return tr == null ? null : tr.methods.get(mLower);
        }
        MethodEntry direct = composed.get(mLower);
        if (direct != null) {
            return direct;
        }
        for (String tn : traitNames) {
            PhpClass tr = resolvedTraits.get(PhpStrings.lower(tn));
            if (tr != null) {
                MethodEntry m = tr.methods.get(mLower);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }

    private void applyPropertyDecl(PhpClassTemplate.PropertyDecl p) {
        this.declaredPropNames.add(p.name());
        if (p.isStatic()) {
            this.staticProperties.put(p.name(), new StaticSlot(p.name(), p.visibility(), this, p.constDefault(), p.defaultTarget()));
        } else if (p.visibility() == Visibility.PRIVATE) {
            String storageKey = "\0" + template.name() + "\0" + p.name();
            Object defVal = p.isReadonly() ? UNINITIALIZED : p.constDefault();
            InstanceProp ip = new InstanceProp(p.name(), storageKey, Visibility.PRIVATE, p.isReadonly(), this, defVal, p.defaultTarget());
            this.instanceProps.add(ip);
            this.privateProps.put(template.lowerName() + "\0" + p.name(), ip);
        } else {
            Object defVal = p.isReadonly() ? UNINITIALIZED : p.constDefault();
            InstanceProp ip = new InstanceProp(p.name(), p.name(), p.visibility(), p.isReadonly(), this, defVal, p.defaultTarget());
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

    public List<PhpClass> usedTraits() {
        return usedTraits;
    }

    public boolean isInterface() {
        return template.isInterface();
    }

    public boolean isTrait() {
        return template.isTrait();
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

    public boolean hasSplInterface(int bit) {
        return (splInterfaces & bit) != 0;
    }

    public MethodEntry methodById(int id) {
        return id < methodsById.length ? methodsById[id] : null;
    }

    @TruffleBoundary
    public MethodEntry lookupMethod(String name) {
        return methods.get(PhpStrings.lower(name));
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
            if (!evaluatingConstants.add(name)) {
                throw new PhpError("Cannot declare self-referencing constant " + template.name() + "::" + name);
            }
            try {
                Object val = evalExpr(own.constValue(), own.valueTarget(), this);
                constants.put(name, val);
                return val;
            } finally {
                evaluatingConstants.remove(name);
            }
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
                return new PropertyResolution(priv.storageKey(), true, Visibility.PRIVATE,
                        priv.isReadonly(), priv.isReadonly(), priv.declaringClass().name());
            }
        }
        InstanceProp vis = visibleProps.get(name);
        if (vis != null) {
            boolean accessible = vis.visibility() == Visibility.PUBLIC
                    || isProtectedAccessible(ctx, vis.declaringClass(), callingClassLower);
            boolean canInit = vis.isReadonly() && isProtectedAccessible(ctx, vis.declaringClass(), callingClassLower);
            return new PropertyResolution(vis.storageKey(), accessible, vis.visibility(),
                    vis.isReadonly(), canInit, vis.declaringClass().name());
        }
        InstanceProp ownPriv = privateProps.get(template.lowerName() + "\0" + name);
        if (ownPriv != null) {
            return new PropertyResolution(ownPriv.storageKey(), false, Visibility.PRIVATE,
                    ownPriv.isReadonly(), false, ownPriv.declaringClass().name());
        }
        return new PropertyResolution(name, true, Visibility.PUBLIC, false, false, null);
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
                vals[i] = evalExpr(p.constDefault(), p.defaultTarget(), this);
            }
            this.defaultPropKeys = keys;
            this.defaultPropValues = vals;
        }
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        for (int i = 0; i < defaultPropKeys.length; i++) {
            put.execute(obj, defaultPropKeys[i], PhpOps.copyOnAssign(defaultPropValues[i]));
        }
    }

    private static Object evalExpr(Object constVal, RootCallTarget target, PhpClass selfClass) {
        if (target == null) {
            return constVal;
        }
        if (target.getRootNode() instanceof PhpExprRootNode ern && ern.hasTraitSelf()) {
            return target.call(selfClass);
        }
        return target.call();
    }

    /** Collects visible property entries for an object from {@code callerClassName} scope. */
    @TruffleBoundary
    public static List<Map.Entry<Object, Object>> visibleEntries(PhpContext ctx, PhpObject obj, String callerClassName) {
        String callerLower = callerClassName == null ? null : PhpStrings.lower(callerClassName);
        PhpClass caller = callerLower == null ? null : ctx.lookupClass(callerLower);
        PhpClass cls = obj.phpClass();
        Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        List<Map.Entry<Object, Object>> out = new ArrayList<>(keys.length);
        for (Object rawKey : keys) {
            String k = (String) rawKey;
            Object rawVal = get.execute(obj, k, PhpNull.INSTANCE);
            if (rawVal == UNINITIALIZED) {
                continue;
            }
            if (k.startsWith("\0")) {
                int sep = k.indexOf('\0', 1);
                String declaring = k.substring(1, sep);
                String prop = k.substring(sep + 1);
                if (callerLower != null && PhpStrings.equalsIgnoreCase(declaring, callerLower)) {
                    out.add(Map.entry(prop, unwrapProp(rawVal)));
                }
                continue;
            }
            InstanceProp p = cls.findVisibleProp(k);
            if (p != null && p.visibility() == Visibility.PROTECTED) {
                if (caller != null && (cls.isInstanceOf(caller) || caller.isInstanceOf(p.declaringClass()))) {
                    out.add(Map.entry(k, unwrapProp(rawVal)));
                }
                continue;
            }
            out.add(Map.entry(k, unwrapProp(rawVal)));
        }
        return out;
    }

    /** Collects visible property entries backed by {@link PhpReference} cells for {@code foreach ($obj as &$v)}. */
    @TruffleBoundary
    public static List<Map.Entry<Object, PhpReference>> visibleRefEntries(PhpContext ctx, PhpObject obj, String callerClassName) {
        String callerLower = callerClassName == null ? null : PhpStrings.lower(callerClassName);
        PhpClass caller = callerLower == null ? null : ctx.lookupClass(callerLower);
        PhpClass cls = obj.phpClass();
        Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        List<Map.Entry<Object, PhpReference>> out = new ArrayList<>(keys.length);
        for (Object rawKey : keys) {
            String k = (String) rawKey;
            if (k.startsWith("\0")) {
                int sep = k.indexOf('\0', 1);
                String declaring = k.substring(1, sep);
                String prop = k.substring(sep + 1);
                if (callerLower != null && PhpStrings.equalsIgnoreCase(declaring, callerLower)) {
                    out.add(Map.entry(prop, ensurePropRef(obj, k, get, put)));
                }
                continue;
            }
            InstanceProp p = cls.findVisibleProp(k);
            if (p != null && p.visibility() == Visibility.PROTECTED) {
                if (caller != null && (cls.isInstanceOf(caller) || caller.isInstanceOf(p.declaringClass()))) {
                    out.add(Map.entry(k, ensurePropRef(obj, k, get, put)));
                }
                continue;
            }
            out.add(Map.entry(k, ensurePropRef(obj, k, get, put)));
        }
        return out;
    }

    private static PhpReference ensurePropRef(PhpObject obj, String storageKey, DynamicObject.GetNode get, DynamicObject.PutNode put) {
        Object cur = get.execute(obj, storageKey, PhpNull.INSTANCE);
        if (cur instanceof PhpReference ref) {
            return ref;
        }
        if (cur instanceof PhpBacktrace.LazyTrace lt) {
            cur = lt.toPhpArray();
        }
        PhpReference ref = new PhpReference(cur == null ? PhpNull.INSTANCE : cur);
        put.execute(obj, storageKey, ref);
        return ref;
    }

    @TruffleBoundary
    public boolean isReadonlyStorageKey(String storageKey) {
        if (storageKey.startsWith("\0")) {
            int sep = storageKey.indexOf('\0', 1);
            String declaring = PhpStrings.lower(storageKey.substring(1, sep));
            String prop = storageKey.substring(sep + 1);
            InstanceProp priv = privateProps.get(declaring + "\0" + prop);
            return priv != null && priv.isReadonly();
        }
        InstanceProp vis = visibleProps.get(storageKey);
        return vis != null && vis.isReadonly();
    }

    public static Object unwrapProp(Object pv) {
        if (pv instanceof PhpReference ref) {
            pv = ref.value;
        }
        if (pv instanceof PhpBacktrace.LazyTrace lt) {
            pv = lt.toPhpArray();
        }
        return pv == null ? PhpNull.INSTANCE : pv;
    }
}
