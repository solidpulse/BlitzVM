package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A linked PHP class, interface, or trait: resolved inheritance links, composed traits,
 * method table, property layout and constant declarations. Immutable after construction
 * and shared by every request that links the same template to the same parent, interfaces
 * and traits (php's immutable classes). What changes per request, static property values
 * and evaluated constants and defaults, lives in the request's {@link RequestState}.
 */
public final class PhpClass {

    public record MethodEntry(String name, PhpClass declaringClass, Visibility visibility,
                              boolean isStatic, boolean isFinal, boolean fromTrait,
                              RootCallTarget callTarget, List<String> parameterNames,
                              long refMask, int variadicIndex) {
        public MethodEntry(String name, PhpClass declaringClass, Visibility visibility,
                           boolean isStatic, boolean isFinal, boolean fromTrait,
                           RootCallTarget callTarget) {
            this(name, declaringClass, visibility, isStatic, isFinal, fromTrait, callTarget, List.of(), 0L, -1);
        }

        public MethodEntry(String name, PhpClass declaringClass, Visibility visibility,
                           boolean isStatic, boolean isFinal, boolean fromTrait,
                           RootCallTarget callTarget, List<String> parameterNames) {
            this(name, declaringClass, visibility, isStatic, isFinal, fromTrait, callTarget, parameterNames, 0L, -1);
        }
    }

    public static final class StaticSlot {
        public final String name;
        public final Visibility visibility;
        public final PhpClass declaringClass;
        private final Object constDefault;
        private final RootCallTarget defaultTarget;
        private final DeclaredType declaredType;
        private final boolean hasDefault;
        private boolean initialized;
        private Object value;

        public StaticSlot(String name, Visibility visibility, PhpClass declaringClass,
                          Object constDefault, RootCallTarget defaultTarget, DeclaredType declaredType,
                          boolean hasDefault) {
            this.name = name;
            this.visibility = visibility;
            this.declaringClass = declaringClass;
            this.constDefault = constDefault;
            this.defaultTarget = defaultTarget;
            this.declaredType = declaredType;
            this.hasDefault = hasDefault;
            this.initialized = defaultTarget == null;
            if (defaultTarget != null) {
                this.value = null;
            } else if (declaredType != null && !hasDefault) {
                this.value = UNINITIALIZED;
            } else {
                this.value = PhpOps.copyOnAssign(constDefault);
            }
        }

        public StaticSlot(String name, Visibility visibility, PhpClass declaringClass,
                          Object constDefault, RootCallTarget defaultTarget) {
            this(name, visibility, declaringClass, constDefault, defaultTarget, null, true);
        }

        /** The declared type, for the property lvalue's check on assignment. */
        public DeclaredType declaredType() {
            return declaredType;
        }

        /** The stored value, possibly a {@link PhpReference} cell; runs the initializer on first use. */
        private Object raw() {
            if (initialized) {
                return value;
            }
            return initializeAndGet();
        }

        /**
         * The first read of a static property, which runs its initializer expression. That runs
         * once per request and pulls in the expression's call machinery, so it stays out of the
         * compiled read: every later static property access is two field reads and a branch.
         */
        @TruffleBoundary
        private Object initializeAndGet() {
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
            return v instanceof PhpReference ref ? getThroughCell(ref) : v;
        }

        /** A reference bound with {@code &Cls::$p}: rare, so its append-buffer handling stays out. */
        @TruffleBoundary
        private static Object getThroughCell(PhpReference ref) {
            return ref.getValue();
        }

        /** Assigns, writing through a reference cell bound to this property. */
        public void set(Object v) {
            if (initialized && value instanceof PhpReference ref) {
                setThroughCell(ref, v);
                return;
            }
            initialized = true;
            value = v;
        }

        @TruffleBoundary
        private static void setThroughCell(PhpReference ref, Object v) {
            ref.setValue(v);
        }

        /** The cell for {@code &Cls::$p}, converting the plain value into one on first use. */
        public PhpReference obtainCell() {
            Object v = raw();
            if (v instanceof PhpReference ref) {
                ref.ensureDefined();
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

        /** Snapshot support: whether the initializer has run (never runs it). */
        public boolean initializedForSnapshot() {
            return initialized;
        }

        /** Snapshot support: the stored value, possibly a reference cell or the uninitialized sentinel. */
        public Object rawValueForSnapshot() {
            return value;
        }

        /** Snapshot support: puts a captured slot back without running its initializer. */
        public void restoreForSnapshot(boolean initialized, Object value) {
            this.initialized = initialized;
            this.value = value;
        }
    }

    /** A declared static property; its value lives in each request's {@link RequestState}. */
    public record StaticDecl(String name, Visibility visibility, PhpClass declaringClass,
                             Object constDefault, RootCallTarget defaultTarget,
                             PhpType type, DeclaredType declaredType, boolean hasDefault,
                             Visibility setVisibility) {

        public StaticDecl(String name, Visibility visibility, PhpClass declaringClass,
                          Object constDefault, RootCallTarget defaultTarget,
                          PhpType type, DeclaredType declaredType, boolean hasDefault) {
            this(name, visibility, declaringClass, constDefault, defaultTarget, type, declaredType, hasDefault, null);
        }

        public StaticDecl(String name, Visibility visibility, PhpClass declaringClass,
                          Object constDefault, RootCallTarget defaultTarget) {
            this(name, visibility, declaringClass, constDefault, defaultTarget, null, null, true);
        }

        public Visibility effectiveSetVisibility() {
            return setVisibility == null ? visibility : setVisibility;
        }

        /** This request's slot for the property, created on first use. */
        @TruffleBoundary
        public StaticSlot slot(PhpContext ctx) {
            RequestState state = ctx.classState(declaringClass);
            StaticSlot slot = state.statics.get(name);
            if (slot == null) {
                slot = new StaticSlot(name, visibility, declaringClass, constDefault, defaultTarget,
                        declaredType, hasDefault);
                state.statics.put(name, slot);
            }
            return slot;
        }
    }

    /** One request's state of one class: static property values, evaluated constants and defaults. */
    public static final class RequestState {
        /** The class whose state this is, for stable declaration indexes, and its request. */
        private final PhpClass cls;
        private final PhpContext ctx;
        private final HashMap<String, StaticSlot> statics = new HashMap<>();
        private HashMap<String, Object> constants;
        private HashSet<String> evaluatingConstants;
        private Object[] defaultValues;
        /** Canonical enum case objects by declaration index, so repeated lookups return one. */
        private PhpObject[] enumCaseObjects;

        public RequestState(PhpClass cls, PhpContext ctx) {
            this.cls = cls;
            this.ctx = ctx;
        }

        /** The class whose state this is. */
        public PhpClass owner() {
            return cls;
        }

        /** A captured static property: whether its initializer ran and the raw stored value. */
        public record StaticValue(boolean initialized, Object value) {
        }

        /**
         * A request's class state at a point in time. The values are raw PHP values (possibly
         * reference cells and arrays); the snapshot layer maps them through its copier. The
         * enum cases are the canonical case objects, so a restore can install the same ones.
         */
        public record StateSnapshot(Map<String, StaticValue> statics,
                                    Map<String, Object> constants,
                                    Object[] defaultValues,
                                    Map<String, Object> enumCases) {
            public StateSnapshot {
                statics = Collections.unmodifiableMap(new LinkedHashMap<>(statics));
                constants = constants == null ? null
                        : Collections.unmodifiableMap(new LinkedHashMap<>(constants));
                defaultValues = defaultValues == null ? null : defaultValues.clone();
                enumCases = enumCases == null ? null
                        : Collections.unmodifiableMap(new LinkedHashMap<>(enumCases));
            }

            public StateSnapshot(Map<String, StaticValue> statics, Map<String, Object> constants,
                                 Object[] defaultValues) {
                this(statics, constants, defaultValues, null);
            }
        }

        public StateSnapshot captureState() {
            Map<String, StaticValue> slots = new LinkedHashMap<>();
            statics.forEach((name, slot) ->
                    slots.put(name, new StaticValue(slot.initializedForSnapshot(), slot.rawValueForSnapshot())));
            return new StateSnapshot(slots,
                    constants == null ? null : new LinkedHashMap<>(constants),
                    defaultValues == null ? null : defaultValues.clone(),
                    snapshotEnumCases());
        }

        /** The canonical case object already created in this request, or null. */
        public Object enumCase(String caseName) {
            int index = cls.template().enumCaseIndex(caseName);
            PhpObject[] cases = enumCaseObjects;
            return index < 0 || cases == null || index >= cases.length ? null : cases[index];
        }

        /** The case at a declaration index, created on first use. */
        public PhpObject enumCaseAt(int index) {
            PhpObject[] cases = enumCaseObjects;
            if (cases != null) {
                PhpObject existing = cases[index];
                if (existing != null) {
                    return existing;
                }
            }
            return createEnumCase(index);
        }

        /** The first use of a case in this request: kept out of compiled code at every use. */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private PhpObject createEnumCase(int index) {
            PhpObject[] cases = enumCaseObjects;
            if (cases == null) {
                cases = enumCaseObjects = new PhpObject[cls.template().enumCases().size()];
            }
            PhpObject existing = cases[index];
            if (existing != null) {
                return existing;
            }
            PhpClassTemplate.EnumCaseDecl decl = cls.template().enumCases().get(index);
            String caseName = decl.name();
            Object value = decl.constValue();
            if (decl.valueTarget() != null) {
                value = cls.evalExpr(decl.constValue(), decl.valueTarget(), cls);
            }
            PhpObject obj = new PhpObject(cls, ctx.nextEnumObjectId());
            DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
            put.execute(obj, "name", caseName);
            boolean backed = cls.template().enumBackingType() != null;
            if (backed) {
                put.execute(obj, "value", value == null ? PhpNull.INSTANCE : value);
            }
            obj.setNativeState(new EnumCaseState(cls.template().name(), caseName, backed, value));
            cases[index] = obj;
            return obj;
        }

        /** Installs a restored canonical enum case object. */
        public void putEnumCase(String caseName, Object value) {
            int index = cls.template().enumCaseIndex(caseName);
            if (index >= 0 && value instanceof PhpObject obj) {
                if (enumCaseObjects == null) {
                    enumCaseObjects = new PhpObject[cls.template().enumCases().size()];
                }
                enumCaseObjects[index] = obj;
            }
        }

        /** The created case objects by name, for snapshots; null when none has been created. */
        private Map<String, Object> snapshotEnumCases() {
            PhpObject[] cases = enumCaseObjects;
            if (cases == null) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            List<PhpClassTemplate.EnumCaseDecl> decls = cls.template().enumCases();
            for (int i = 0; i < cases.length && i < decls.size(); i++) {
                if (cases[i] != null) {
                    out.put(decls.get(i).name(), cases[i]);
                }
            }
            return out.isEmpty() ? null : out;
        }

    }

    /** Restores a captured class state; the values must already be mapped into this request. */
    @TruffleBoundary
    public void restoreState(PhpContext ctx, RequestState.StateSnapshot snap) {
        RequestState state = ctx.classState(this);
        for (Map.Entry<String, RequestState.StaticValue> entry : snap.statics().entrySet()) {
            StaticDecl decl = staticDecls.get(entry.getKey());
            StaticSlot slot = decl != null
                    ? decl.slot(ctx)
                    : new StaticSlot(entry.getKey(), Visibility.PUBLIC, this, PhpNull.INSTANCE, null);
            RequestState.StaticValue value = entry.getValue();
            slot.restoreForSnapshot(value.initialized(), value.value());
        }
        if (snap.constants() != null) {
            state.constants = new HashMap<>(snap.constants());
            state.evaluatingConstants = new HashSet<>();
        }
        if (snap.defaultValues() != null) {
            state.defaultValues = snap.defaultValues().clone();
        }
        if (snap.enumCases() != null) {
            for (Map.Entry<String, Object> entry : snap.enumCases().entrySet()) {
                state.putEnumCase(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Property defaults: storage keys and constant values, or null where an initializer must run. */
    private record Defaults(String[] keys, Object[] values, boolean dynamic) {
    }

    /** Sentinel stored in {@link PhpObject} for an uninitialized {@code readonly} property. */
    public static final Object UNINITIALIZED = new Object();

    /** One hook of an effective property: php's {@code $prop::get} or {@code $prop::set}. */
    public record PropertyHook(String name, PhpClass declaringClass, RootCallTarget callTarget,
                               boolean byRef, boolean isFinal, boolean fromTrait, boolean backs,
                               PhpSignature signature) {
        public boolean isAbstract() {
            return callTarget == null;
        }
    }

    public record InstanceProp(String name, String storageKey, Visibility visibility, Visibility setVisibility,
                               boolean isReadonly, PhpClass declaringClass, Object constDefault,
                               RootCallTarget defaultTarget, PhpType type, DeclaredType declaredType,
                               PropertyHook getHook, PropertyHook setHook, boolean virtual, boolean isFinal,
                               boolean writeBlocked) {

        public InstanceProp(String name, String storageKey, Visibility visibility, boolean isReadonly,
                            PhpClass declaringClass, Object constDefault, RootCallTarget defaultTarget,
                            PhpType type, DeclaredType declaredType) {
            this(name, storageKey, visibility, null, isReadonly, declaringClass, constDefault, defaultTarget,
                    type, declaredType, null, null, false, false, false);
        }

        public Visibility effectiveSetVisibility() {
            return setVisibility == null ? visibility : setVisibility;
        }

        public boolean hasHooks() {
            return getHook != null || setHook != null;
        }
    }

    public record PropertyResolution(String storageKey, boolean accessible, Visibility visibility,
                                     boolean isReadonly, boolean canInitReadonly, String declaringClass,
                                     DeclaredType type, boolean setAccessible, Visibility setVisibility,
                                     String setDeclaringClass, PropertyHook getHook, PropertyHook setHook,
                                     boolean virtual, boolean hooked, boolean writeBlocked) {

        /** A resolution without hooks or asymmetric visibility, for callers that have none. */
        public PropertyResolution(String storageKey, boolean accessible, Visibility visibility,
                                  boolean isReadonly, boolean canInitReadonly, String declaringClass,
                                  DeclaredType type) {
            this(storageKey, accessible, visibility, isReadonly, canInitReadonly, declaringClass, type,
                    accessible, visibility, declaringClass, null, null, false, false, false);
        }

        public boolean writable() {
            return setAccessible;
        }

        public boolean hasHooks() {
            return hooked;
        }
    }

    private final PhpClassTemplate template;
    private final long hierarchyId;
    private final int classId;
    private final PhpLanguage language;
    private final PhpClass parent;
    private final List<PhpClass> interfaces;
    /**
     * The engine-wide name ids of this class and every ancestor: itself, its parent chain, every
     * implemented interface and the interfaces those extend, including interfaces added
     * implicitly ({@code Stringable}, {@code UnitEnum}, {@code BackedEnum}). Sorted ascending
     * and immutable, built once when the class is linked. Lets a class check membership by name
     * id without a request's class table; {@link #isInstanceOf} and the declared type guards use
     * it.
     */
    @CompilationFinal(dimensions = 1)
    private final int[] ancestorIds;

    private final List<PhpClass> usedTraits;
    private final List<PhpClassTemplate.PropertyDecl> traitProperties;
    private final List<PhpClassTemplate.ConstantDecl> traitConstants;
    private final Map<String, MethodEntry> methods = new HashMap<>();
    private MethodEntry[] methodsById;
    private final Map<String, StaticDecl> staticDecls = new HashMap<>();
    private final Map<String, PhpClassTemplate.ConstantDecl> constantDecls = new LinkedHashMap<>();
    private final List<InstanceProp> instanceProps = new ArrayList<>();
    private final Map<String, InstanceProp> visibleProps = new HashMap<>();
    private final Map<String, InstanceProp> privateProps = new HashMap<>();
    private final Set<String> declaredPropNames = new HashSet<>();

    private volatile Defaults defaults;
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
    /**
     * Whether an instance may carry an {@link SplArrayState} (ArrayObject, ArrayIterator and their
     * subclasses). A property access can only reach the array behind such an object, so every
     * other class skips checking the native state.
     */
    private final boolean splArrayState;

    public PhpClass(PhpLanguage language, PhpClassTemplate template, PhpClass parent, List<PhpClass> interfaces) {
        this(language, template, parent, interfaces, Map.of());
    }

    public PhpClass(PhpLanguage language, PhpClassTemplate template, PhpClass parent,
                    List<PhpClass> interfaces, Map<String, PhpClass> resolvedTraits) {
        this.template = template;
        this.language = language;
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
        this.splArrayState = "arrayobject".equals(template.lowerName())
                || "arrayiterator".equals(template.lowerName())
                || (parent != null && parent.splArrayState);
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
        this.classId = language.classId(template.lowerName());
        language.noteHierarchy(classId, hierarchyId);

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
            MethodEntry entry = methodEntry(m, ownIsTrait);
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
            this.staticDecls.putAll(parent.staticDecls);
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
                    applyPropertyDecl(tp, true);
                }
            }
        }
        allTraitProps.addAll(template.properties());
        this.traitProperties = ownIsTrait ? List.copyOf(allTraitProps) : List.of();

        // Apply own property declarations (overriding non-private properties in place).
        for (PhpClassTemplate.PropertyDecl p : template.properties()) {
            applyPropertyDecl(p, false);
        }

        validateAbstractHooks();

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

        if (template.isEnum()) {
            // Enum cases expose readonly name (and value, when backed) properties. Declaring
            // them here gives property access, isset, unset and reflection the right answers,
            // and keeps the case object from growing dynamic properties.
            addEnumProperty("name");
            if (template.enumBackingType() != null) {
                addEnumProperty("value");
            }
            putMethod("cases", language.methodId("cases"), new MethodEntry("cases", this,
                    Visibility.PUBLIC, true, false, false, EnumBuiltins.callTarget(language, this, "cases")));
            if (template.enumBackingType() != null) {
                putMethod("from", language.methodId("from"), new MethodEntry("from", this,
                        Visibility.PUBLIC, true, false, false, EnumBuiltins.callTarget(language, this, "from")));
                putMethod("tryfrom", language.methodId("tryFrom"), new MethodEntry("tryFrom", this,
                        Visibility.PUBLIC, true, false, false, EnumBuiltins.callTarget(language, this, "tryFrom")));
            }
        }
        this.ancestorIds = ancestryIds(this);
    }

    /**
     * The sorted, deduplicated name ids of {@code self}, its parent chain, and every interface
     * those implement, directly or through other interfaces. Each parent and interface already
     * has its own complete set, so this is an assembly rather than a walk of the hierarchy.
     */
    private static int[] ancestryIds(PhpClass self) {
        int[] parentIds = self.parent == null ? EMPTY_IDS : self.parent.ancestorIds;
        List<PhpClass> ifaces = self.interfaces;
        int size = 1 + parentIds.length;
        for (int i = 0; i < ifaces.size(); i++) {
            size += ifaces.get(i).ancestorIds.length;
        }
        int[] ids = new int[size];
        ids[0] = self.classId;
        System.arraycopy(parentIds, 0, ids, 1, parentIds.length);
        int n = 1 + parentIds.length;
        for (int i = 0; i < ifaces.size(); i++) {
            int[] ifaceIds = ifaces.get(i).ancestorIds;
            System.arraycopy(ifaceIds, 0, ids, n, ifaceIds.length);
            n += ifaceIds.length;
        }
        java.util.Arrays.sort(ids, 0, n);
        int unique = 0;
        for (int i = 0; i < n; i++) {
            if (unique == 0 || ids[unique - 1] != ids[i]) {
                ids[unique++] = ids[i];
            }
        }
        return unique == n ? ids : java.util.Arrays.copyOf(ids, unique);
    }

    private static final int[] EMPTY_IDS = new int[0];

    private void addEnumProperty(String name) {
        InstanceProp prop = new InstanceProp(name, name, Visibility.PUBLIC, true, this,
                PhpNull.INSTANCE, null, null, null);
        declaredPropNames.add(name);
        visibleProps.put(name, prop);
        instanceProps.add(prop);
    }

    private void putMethod(String lower, int methodId, MethodEntry entry) {
        this.methods.put(lower, entry);
        if (methodId >= this.methodsById.length) {
            this.methodsById = java.util.Arrays.copyOf(this.methodsById, Math.max(methodId + 1, this.methodsById.length * 2));
        }
        this.methodsById[methodId] = entry;
    }

    /**
     * The linked entry for a method declared on this template. An explicit signature (guest
     * code, or a Java method that registers its names) supplies the names; a Java method
     * without one takes php's names from the generated {@link InternalParamNames} table.
     */
    private MethodEntry methodEntry(PhpClassTemplate.MethodDecl m, boolean fromTrait) {
        List<String> names = List.of();
        long refMask = 0L;
        int variadic = -1;
        if (m.signature() != null) {
            List<PhpParameter> parameters = m.signature().parameters();
            List<String> declared = new ArrayList<>(parameters.size());
            for (int i = 0; i < parameters.size(); i++) {
                PhpParameter parameter = parameters.get(i);
                declared.add(parameter.name());
                if (parameter.byRef() && i < 64) {
                    refMask |= 1L << i;
                }
                if (parameter.variadic()) {
                    variadic = i;
                }
            }
            names = List.copyOf(declared);
        } else {
            InternalParamNames.Params params = InternalParamNames.forMethod(template.lowerName(), m.lowerName());
            if (params != null) {
                names = params.names();
                refMask = params.refMask();
                variadic = params.variadicIndex();
            }
        }
        return new MethodEntry(m.name(), this, m.visibility(), m.isStatic(), m.isFinal(),
                fromTrait, m.callTarget(), names, refMask, variadic);
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

    private void applyPropertyDecl(PhpClassTemplate.PropertyDecl p, boolean fromTrait) {
        this.declaredPropNames.add(p.name());
        DeclaredType declaredType = DeclaredType.of(p.type(), template.name(), language);
        if (p.isStatic()) {
            StaticDecl prev = this.staticDecls.get(p.name());
            if (prev != null && prev.declaringClass() != this) {
                checkSetVisibility(prev.effectiveSetVisibility(), p, prev.declaringClass(), true);
            }
            this.staticDecls.put(p.name(), new StaticDecl(p.name(), p.visibility(), this, p.constDefault(),
                    p.defaultTarget(), p.type(), declaredType, p.hasDefault(), p.setVisibility()));
            return;
        }
        if (p.visibility() == Visibility.PRIVATE) {
            String storageKey = "\0" + template.name() + "\0" + p.name();
            Object defVal = defaultFor(p);
            InstanceProp ip = new InstanceProp(p.name(), storageKey, Visibility.PRIVATE, p.setVisibility(),
                    p.isReadonly(), this, defVal, p.defaultTarget(), p.type(), declaredType,
                    hook(p.getHook(), fromTrait), hook(p.setHook(), fromTrait), p.virtual(),
                    p.setVisibility() == Visibility.PRIVATE, p.writeBlocked());
            this.instanceProps.add(ip);
            this.privateProps.put(template.lowerName() + "\0" + p.name(), ip);
            return;
        }

        InstanceProp prev = this.visibleProps.get(p.name());
        if (prev != null) {
            checkPropertyOverride(prev, p);
        }
        // Hooks are inherited per hook: a child declaration overrides the hooks it declares
        // and keeps the rest. A plain redeclaration keeps all of them but makes the property
        // backed, as php 8.4 does.
        PropertyHook getHook = p.getHook() != null ? hook(p.getHook(), fromTrait)
                : (prev != null && prev.getHook() != null && !prev.getHook().isAbstract() ? prev.getHook() : null);
        PropertyHook setHook = p.setHook() != null ? hook(p.setHook(), fromTrait)
                : (prev != null && prev.setHook() != null && !prev.setHook().isAbstract() ? prev.setHook() : null);
        boolean virtual;
        if (prev != null && !p.hasHooks()) {
            virtual = false;
        } else {
            virtual = (getHook != null || setHook != null)
                    && !(getHook != null && getHook.backs())
                    && !(setHook != null && setHook.backs());
            if (prev != null && !prev.virtual()) {
                // Backedness is inherited: a child's virtual-looking hooks still read the store.
                virtual = false;
            }
        }
        Object defVal = virtual ? PhpNull.INSTANCE : defaultFor(p);
        String storageKey = prev != null ? prev.storageKey() : p.name();
        // A redeclaration without a set modifier widens writes to its own read visibility;
        // php does not inherit the parent's asymmetric set level (x6 in the task notes).
        Visibility setVisibility = p.setVisibility();
        InstanceProp ip = new InstanceProp(p.name(), storageKey, p.visibility(), setVisibility, p.isReadonly(),
                this, defVal, p.defaultTarget(), p.type(), declaredType, getHook, setHook, virtual,
                p.setVisibility() == Visibility.PRIVATE, p.writeBlocked());
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
        this.visibleProps.put(p.name(), ip);
    }

    private PropertyHook hook(PhpClassTemplate.HookDecl decl, boolean fromTrait) {
        if (decl == null) {
            return null;
        }
        return new PropertyHook(decl.name(), this, decl.callTarget(), decl.byRef(), decl.isFinal(),
                fromTrait, decl.backs(), decl.signature());
    }

    private void checkPropertyOverride(InstanceProp prev, PhpClassTemplate.PropertyDecl p) {
        if (prev.isFinal()) {
            throw PhpError.fatal("Cannot override final property " + prev.declaringClass().name() + "::$" + p.name());
        }
        if (p.visibility().ordinal() > prev.visibility().ordinal()) {
            String level = prev.visibility() == Visibility.PUBLIC ? "public" : "protected";
            throw PhpError.fatal("Access level to " + template.name() + "::$" + p.name()
                    + " must be " + level + " (as in class " + prev.declaringClass().name() + ")");
        }
        checkSetVisibility(prev.effectiveSetVisibility(), p, prev.declaringClass(), prev.setVisibility() != null);
        if (prev.getHook() != null && prev.getHook().isFinal() && p.getHook() != null) {
            throw PhpError.fatal("Cannot override final property hook "
                    + prev.getHook().declaringClass().name() + "::$" + p.name() + "::get()");
        }
        if (prev.setHook() != null && prev.setHook().isFinal() && p.setHook() != null) {
            throw PhpError.fatal("Cannot override final property hook "
                    + prev.setHook().declaringClass().name() + "::$" + p.name() + "::set()");
        }
        if (prev.type() != null && p.type() != null && !prev.type().toString().equals(p.type().toString())) {
            if (prev.getHook() != null && p.getHook() != null) {
                throw PhpError.fatal("Declaration of " + template.name() + "::$" + p.name()
                        + "::get(): " + p.type() + " must be compatible with "
                        + prev.declaringClass().name() + "::$" + p.name() + "::get(): " + prev.type());
            }
            throw PhpError.fatal("Type of " + template.name() + "::$" + p.name() + " must be "
                    + prev.type() + " (as in class " + prev.declaringClass().name() + ")");
        }
    }

    private void checkSetVisibility(Visibility parentSet, PhpClassTemplate.PropertyDecl p,
                                    PhpClass parentDeclaring, boolean parentAsymmetric) {
        Visibility childSet = p.setVisibility() != null ? p.setVisibility() : p.visibility();
        if (parentSet == Visibility.PUBLIC && childSet != Visibility.PUBLIC) {
            throw PhpError.fatal("Set access level of " + template.name() + "::$" + p.name()
                    + " must be omitted (as in class " + parentDeclaring.name() + ")");
        }
        if (parentSet != Visibility.PUBLIC && childSet.ordinal() > parentSet.ordinal()) {
            throw PhpError.fatal("Set access level of " + template.name() + "::$" + p.name()
                    + " must be " + setLevel(parentSet) + " (as in class " + parentDeclaring.name()
                    + ") or weaker");
        }
    }

    private static String setLevel(Visibility visibility) {
        return visibility == Visibility.PRIVATE ? "private(set)" : "protected(set)";
    }

    /** Concrete classes must implement every abstract hook they inherit or promise an interface. */
    private void validateAbstractHooks() {
        if (template.isInterface() || template.isTrait() || template.isAbstract()) {
            return;
        }
        for (InstanceProp prop : instanceProps) {
            if (prop.getHook() != null && prop.getHook().isAbstract()) {
                throw abstractHook(prop.getHook().declaringClass(), prop.name(), "get", "method");
            }
            if (prop.setHook() != null && prop.setHook().isAbstract()) {
                throw abstractHook(prop.setHook().declaringClass(), prop.name(), "set", "method");
            }
        }
        validateInterfacePropertyContracts();
    }

    private void validateInterfacePropertyContracts() {
        Set<PhpClass> seen = new HashSet<>();
        List<PhpClass> todo = new ArrayList<>();
        for (PhpClass c = this; c != null; c = c.parent) {
            todo.addAll(c.interfaces);
        }
        while (!todo.isEmpty()) {
            PhpClass iface = todo.remove(todo.size() - 1);
            if (!seen.add(iface)) {
                continue;
            }
            todo.addAll(iface.interfaces());
            for (PhpClassTemplate.PropertyDecl decl : iface.template().properties()) {
                InstanceProp prop = decl.visibility() == Visibility.PRIVATE
                        ? privateProps.get(iface.lowerName() + "\0" + decl.name())
                        : visibleProps.get(decl.name());
                if (prop == null) {
                    throw abstractHook(iface, decl.name(), decl.getHook() != null ? "get" : "set", "method");
                }
                if (prop.visibility() != Visibility.PUBLIC) {
                    throw PhpError.fatal("Access level to " + template.name() + "::$" + decl.name()
                            + " must be public (as in interface " + iface.name() + ")");
                }
                if (decl.type() != null && prop.type() != null
                        && !interfaceTypeCompatible(prop.type(), decl.type())) {
                    String hook = decl.getHook() != null ? "get" : "set";
                    throw PhpError.fatal("Declaration of " + template.name() + "::$" + decl.name() + "::" + hook
                            + "(): " + prop.type() + " must be compatible with "
                            + iface.name() + "::$" + decl.name() + "::" + hook + "(): " + decl.type());
                }
                if (decl.getHook() != null) {
                    if (prop.virtual() && prop.getHook() == null
                            || prop.getHook() != null && prop.getHook().isAbstract()) {
                        throw abstractHook(iface, decl.name(), "get", "method");
                    }
                }
                if (decl.setHook() != null) {
                    if (prop.setVisibility() != null) {
                        throw PhpError.fatal("Set access level of " + template.name() + "::$" + decl.name()
                                + " must be omitted (as in class " + iface.name() + ")");
                    }
                    if (prop.virtual() && prop.setHook() == null
                            || prop.setHook() != null && prop.setHook().isAbstract()) {
                        throw abstractHook(iface, decl.name(), "set", "method");
                    }
                }
            }
        }
    }

    /** Interface property get types are covariant; nullable widening is the common case. */
    private static boolean interfaceTypeCompatible(PhpType child, PhpType parent) {
        if (parent instanceof PhpType.Nullable nullable) {
            if (child instanceof PhpType.Nullable childNullable) {
                return interfaceTypeCompatible(childNullable.inner(), nullable.inner());
            }
            return interfaceTypeCompatible(child, nullable.inner());
        }
        if (child instanceof PhpType.Nullable) {
            return false;
        }
        if (parent instanceof PhpType.Named named
                && PhpStrings.equalsIgnoreCase(named.name(), "mixed")) {
            return true;
        }
        if (parent instanceof PhpType.Union union) {
            if (child instanceof PhpType.Union childUnion) {
                for (PhpType member : childUnion.members()) {
                    if (!interfaceTypeCompatible(member, union)) {
                        return false;
                    }
                }
                return true;
            }
            for (PhpType member : union.members()) {
                if (interfaceTypeCompatible(child, member)) {
                    return true;
                }
            }
            return false;
        }
        return child.toString().equalsIgnoreCase(parent.toString());
    }

    private PhpError abstractHook(PhpClass owner, String prop, String hook, String noun) {
        return PhpError.fatal("Class " + template.name() + " contains 1 abstract method and must therefore"
                + " be declared abstract or implement the remaining " + noun + " ("
                + owner.name() + "::$" + prop + "::" + hook + ")");
    }

    /** A typed property without an explicit default starts uninitialized, as php does. */
    private static Object defaultFor(PhpClassTemplate.PropertyDecl p) {
        if (p.isReadonly()) {
            return UNINITIALIZED;
        }
        if (p.type() != null && !p.hasDefault()) {
            return UNINITIALIZED;
        }
        return p.constDefault();
    }

    public PhpClassTemplate template() {
        return template;
    }

    /** A readonly class cannot grow dynamic properties. */
    public boolean isReadonlyClass() {
        return template.isReadonly();
    }

    public long hierarchyId() {
        return hierarchyId;
    }

    /** The engine-wide id of this class's name, the index into each context's class table. */
    public int classId() {
        return classId;
    }

    public String name() {
        return template.name();
    }

    /** The lowercase class name, for case-insensitive lookups and table keys. */
    public String lowerName() {
        return template.lowerName();
    }

    /** Whether this class comes from the runtime's builtin template set rather than user code. */
    @TruffleBoundary
    public boolean isBuiltin() {
        return language.builtinClassTemplate(template.lowerName()) != null;
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

    /** Whether instances may carry an ArrayObject-style {@link SplArrayState}. */
    public boolean mayHaveSplArrayState() {
        return splArrayState;
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

    /**
     * Whether this class is the class of name id {@code targetId} or a descendant or
     * implementor of it. Name ids are engine-wide and unique per lower-case class name, and a
     * request has at most one class per name, so the set is exact for values of this request.
     * {@code class_alias} names are not in any set: the declared type guard falls through to
     * the exact lookup for those.
     */
    public boolean matchesNameId(int targetId) {
        int[] ids = ancestorIds;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == targetId) {
                return true;
            }
            if (ids[i] > targetId) {
                return false;
            }
        }
        return false;
    }

    public boolean isInstanceOf(PhpClass target) {
        return target != null && matchesNameId(target.classId);
    }

    /** This request's slot for a static property, or null if the class has none by that name. */
    @TruffleBoundary
    public StaticSlot lookupStaticProperty(PhpContext ctx, String name) {
        StaticDecl decl = staticDecls.get(name);
        return decl == null ? null : decl.slot(ctx);
    }

    /** The declaration behind {@code Cls::$name}, or null if there is none. Fixed per class. */
    @TruffleBoundary
    public StaticDecl lookupStaticDecl(String name) {
        return staticDecls.get(name);
    }

    @TruffleBoundary
    public boolean hasDeclaredProperty(String name) {
        return declaredPropNames.contains(name);
    }

    @TruffleBoundary
    public InstanceProp findVisibleProp(String name) {
        return visibleProps.get(name);
    }

    /** The private property named {@code name} declared closest to this class, or null. */
    @TruffleBoundary
    public InstanceProp findPrivateProp(String name) {
        for (int i = instanceProps.size() - 1; i >= 0; i--) {
            InstanceProp p = instanceProps.get(i);
            if (p.visibility() == Visibility.PRIVATE && p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** The private property {@code name} declared exactly in {@code declaringClass}, or null. */
    @TruffleBoundary
    public InstanceProp findPrivatePropDeclaredIn(String declaringClass, String name) {
        for (int i = instanceProps.size() - 1; i >= 0; i--) {
            InstanceProp p = instanceProps.get(i);
            if (p.visibility() == Visibility.PRIVATE && p.name().equals(name)
                    && PhpStrings.equalsIgnoreCase(p.declaringClass().name(), declaringClass)) {
                return p;
            }
        }
        return null;
    }

    /** The declared instance property stored under {@code storageKey}, or null. */
    @TruffleBoundary
    public InstanceProp findPropByStorageKey(String storageKey) {
        for (int i = instanceProps.size() - 1; i >= 0; i--) {
            InstanceProp p = instanceProps.get(i);
            if (p.storageKey().equals(storageKey)) {
                return p;
            }
        }
        return null;
    }

    /** The canonical object for an enum case in this request, or null if the case is unknown. */
    @TruffleBoundary
    public Object enumCaseValue(PhpContext ctx, String caseName) {
        int index = template.enumCaseIndex(caseName);
        return index < 0 ? null : ctx.classState(this).enumCaseAt(index);
    }

    /** The case object at a declaration index, created on first use (one per request). */
    public PhpObject enumCaseAt(PhpContext ctx, int index) {
        return ctx.classState(this).enumCaseAt(index);
    }

    /**
     * The value of {@code Cls::NAME} when it is the same in every request, or null when the
     * constant needs the full lookup: an initializer to evaluate or an unresolved name. The walk
     * is the own declaration, the parent chain, then the interfaces, as {@link #lookupConstant}
     * does, so the two stay in step.
     */
    @TruffleBoundary
    public Object lookupStaticConstant(String name) {
        Object v = findStaticConstant(name);
        return v == NEEDS_REQUEST ? null : v;
    }

    /** Marks a constant found with an initializer: the walk stops there, as in lookupConstant. */
    private static final Object NEEDS_REQUEST = new Object();

    private Object findStaticConstant(String name) {
        if (name.equalsIgnoreCase("class")) {
            return template.name();
        }
        PhpClassTemplate.ConstantDecl own = constantDecls.get(name);
        if (own != null) {
            return own.valueTarget() == null ? own.constValue() : NEEDS_REQUEST;
        }
        if (parent != null) {
            Object p = parent.findStaticConstant(name);
            if (p != null) {
                return p;
            }
        }
        for (PhpClass iface : interfaces) {
            Object iv = iface.findStaticConstant(name);
            if (iv != null) {
                return iv;
            }
        }
        return null;
    }

    @TruffleBoundary
    public Object lookupConstant(PhpContext ctx, String name) {
        if (name.equalsIgnoreCase("class")) {
            return template.name();
        }
        if (template.isEnum()) {
            Object caseValue = enumCaseValue(ctx, name);
            if (caseValue != null) {
                return caseValue;
            }
        }
        PhpClassTemplate.ConstantDecl own = constantDecls.get(name);
        if (own != null) {
            if (own.valueTarget() == null) {
                return own.constValue();
            }
            // An initializer can read global constants, which differ per request.
            RequestState state = ctx.classState(this);
            if (state.constants == null) {
                state.constants = new HashMap<>();
                state.evaluatingConstants = new HashSet<>();
            }
            Object cached = state.constants.get(name);
            if (cached != null) {
                return cached;
            }
            if (!state.evaluatingConstants.add(name)) {
                throw new PhpError("Cannot declare self-referencing constant " + template.name() + "::" + name);
            }
            try {
                Object val = evalExpr(own.constValue(), own.valueTarget(), this);
                state.constants.put(name, val);
                return val;
            } finally {
                state.evaluatingConstants.remove(name);
            }
        }
        if (parent != null) {
            Object p = parent.lookupConstant(ctx, name);
            if (p != null) {
                return p;
            }
        }
        for (PhpClass iface : interfaces) {
            Object iv = iface.lookupConstant(ctx, name);
            if (iv != null) {
                return iv;
            }
        }
        return null;
    }

    @TruffleBoundary
    public PropertyResolution resolveProperty(PhpContext ctx, String name, String callingClassLower) {
        if (callingClassLower != null) {
            InstanceProp priv = privateProps.get(callingClassLower + "\0" + name);
            if (priv != null) {
                return resolution(priv, true, priv.effectiveSetVisibility() == Visibility.PRIVATE
                        || effectiveWriteAccess(priv, ctx, callingClassLower));
            }
        }
        InstanceProp vis = visibleProps.get(name);
        if (vis != null) {
            boolean accessible = vis.visibility() == Visibility.PUBLIC
                    || isProtectedAccessible(ctx, vis.declaringClass(), callingClassLower);
            boolean canInit = vis.isReadonly() && isProtectedAccessible(ctx, vis.declaringClass(), callingClassLower);
            return resolution(vis, accessible, effectiveWriteAccess(vis, ctx, callingClassLower), canInit);
        }
        InstanceProp ownPriv = privateProps.get(template.lowerName() + "\0" + name);
        if (ownPriv != null) {
            return resolution(ownPriv, false, false);
        }
        return new PropertyResolution(name, true, Visibility.PUBLIC, false, false, null, null,
                true, Visibility.PUBLIC, null, null, null, false, false, false);
    }

    private PropertyResolution resolution(InstanceProp prop, boolean accessible, boolean writable) {
        return resolution(prop, accessible, writable, prop.isReadonly() && accessible);
    }

    private PropertyResolution resolution(InstanceProp prop, boolean accessible, boolean writable,
                                          boolean canInitReadonly) {
        return new PropertyResolution(prop.storageKey(), accessible, prop.visibility(), prop.isReadonly(),
                canInitReadonly, prop.declaringClass().name(), prop.declaredType(), writable,
                prop.effectiveSetVisibility(), prop.declaringClass().name(), prop.getHook(), prop.setHook(),
                prop.virtual(), prop.hasHooks(), prop.writeBlocked());
    }

    /** Whether a caller in {@code callingClassLower} may write the property's set visibility. */
    private boolean effectiveWriteAccess(InstanceProp prop, PhpContext ctx, String callingClassLower) {
        Visibility set = prop.effectiveSetVisibility();
        return switch (set) {
            case PUBLIC -> prop.visibility() == Visibility.PUBLIC
                    || isProtectedAccessible(ctx, prop.declaringClass(), callingClassLower);
            case PROTECTED -> isProtectedAccessible(ctx, prop.declaringClass(), callingClassLower);
            case PRIVATE -> callingClassLower != null
                    && PhpStrings.equalsIgnoreCase(prop.declaringClass().name(), callingClassLower);
        };
    }

    private boolean isProtectedAccessible(PhpContext ctx, PhpClass declaring, String callingClassLower) {
        if (callingClassLower == null) {
            return false;
        }
        PhpClass caller = ctx.lookupClass(callingClassLower);
        return caller != null && (this.isInstanceOf(caller) || caller.isInstanceOf(declaring));
    }

    /** The class a private(set) or private write names in php's "from scope X" message. */
    @TruffleBoundary
    public static String scopeName(PhpContext ctx, String callingClassLower) {
        if (callingClassLower == null || callingClassLower.isEmpty()) {
            return "global scope";
        }
        PhpClass caller = ctx.lookupClass(callingClassLower);
        if (caller == null) {
            return "global scope";
        }
        return "scope " + caller.name();
    }

    @TruffleBoundary
    public void populateDefaults(PhpObject obj) {
        if (instanceProps.isEmpty()) {
            return;
        }
        Defaults d = defaults();
        Object[] vals = d.dynamic() ? dynamicDefaultValues(d) : d.values();
        String[] keys = d.keys();
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        for (int i = 0; i < keys.length; i++) {
            put.execute(obj, keys[i], PhpOps.copyOnAssign(vals[i]));
        }
    }

    /**
     * Storage keys of the declared instance properties, in the order {@link #defaultValues()}
     * returns their values. Fixed for the life of the class, so a {@code new} site can cache one
     * PutNode per key.
     */
    @TruffleBoundary
    public String[] defaultKeys() {
        return defaults().keys();
    }

    /**
     * Default values matching {@link #defaultKeys()} when none depends on the request (they are
     * then the same array for every object, so a {@code new} site can cache it); else null.
     */
    @TruffleBoundary
    public Object[] constantDefaultValuesOrNull() {
        Defaults d = defaults();
        return d.dynamic() ? null : d.values();
    }

    /** Default values matching {@link #defaultKeys()}, evaluated for this request; not yet copied for assignment. */
    @TruffleBoundary
    public Object[] defaultValues() {
        Defaults d = defaults();
        return d.dynamic() ? dynamicDefaultValues(d) : d.values();
    }

    private Defaults defaults() {
        Defaults d = defaults;
        if (d == null) {
            d = buildDefaults();
            defaults = d;
        }
        return d;
    }

    /** Initializers can read global constants, so they run once per request. */
    private Object[] dynamicDefaultValues(Defaults d) {
        RequestState state = PhpContext.get(null).classState(this);
        if (state.defaultValues == null) {
            List<InstanceProp> backed = storageProps();
            Object[] evaluated = d.values().clone();
            for (int i = 0; i < evaluated.length; i++) {
                InstanceProp p = backed.get(i);
                if (p.defaultTarget() != null) {
                    evaluated[i] = evalExpr(p.constDefault(), p.defaultTarget(), this);
                }
            }
            state.defaultValues = evaluated;
        }
        return state.defaultValues;
    }

    /** The properties that have backing storage; virtual properties are not populated. */
    private List<InstanceProp> storageProps() {
        List<InstanceProp> out = new ArrayList<>(instanceProps.size());
        for (InstanceProp p : instanceProps) {
            if (!p.virtual()) {
                out.add(p);
            }
        }
        return out;
    }

    private Defaults buildDefaults() {
        List<InstanceProp> backed = storageProps();
        String[] keys = new String[backed.size()];
        Object[] vals = new Object[backed.size()];
        boolean dynamic = false;
        for (int i = 0; i < backed.size(); i++) {
            InstanceProp p = backed.get(i);
            keys[i] = p.storageKey();
            if (p.defaultTarget() != null) {
                dynamic = true;
            } else {
                vals[i] = p.constDefault();
            }
        }
        return new Defaults(keys, vals, dynamic);
    }

    private static Object evalExpr(Object constVal, RootCallTarget target, PhpClass selfClass) {
        if (target == null) {
            return constVal;
        }
        if (target.getRootNode() instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode brn
                && brn.hasTraitSelf()) {
            return target.call(selfClass);
        }
        return target.call();
    }

    /** The default (not current) value of a declared property, for get_class_vars(). */
    @TruffleBoundary
    public Object evaluatePropertyDefault(PhpClassTemplate.PropertyDecl p) {
        return PhpOps.copyOnAssign(evalExpr(p.constDefault(), p.defaultTarget(), this));
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
        Set<String> handled = new HashSet<>();
        for (InstanceProp p : cls.instanceProps) {
            boolean visible;
            if (p.visibility() == Visibility.PUBLIC) {
                visible = true;
            } else if (p.visibility() == Visibility.PROTECTED) {
                visible = caller != null && (cls.isInstanceOf(caller) || caller.isInstanceOf(p.declaringClass()));
            } else {
                visible = callerLower != null && PhpStrings.equalsIgnoreCase(p.declaringClass().name(), callerLower);
            }
            if (!visible) {
                handled.add(p.storageKey());
                continue;
            }
            handled.add(p.storageKey());
            Object value = readForEnumeration(obj, p, get);
            if (value != NO_VALUE) {
                out.add(Map.entry(p.name(), value));
            }
        }
        for (Object rawKey : keys) {
            String k = (String) rawKey;
            if (handled.contains(k)) {
                continue;
            }
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
            out.add(Map.entry(k, unwrapProp(rawVal)));
        }
        return out;
    }

    private static final Object NO_VALUE = new Object();

    private static Object readForEnumeration(PhpObject obj, InstanceProp prop, DynamicObject.GetNode get) {
        if (prop.virtual()) {
            if (prop.getHook() == null || prop.getHook().isAbstract()) {
                return NO_VALUE;
            }
            return unwrapProp(callHook(obj, prop, prop.getHook(), null, PhpObject.GUARD_HOOK_GET));
        }
        Object rawVal = get.execute(obj, prop.storageKey(), PhpNull.INSTANCE);
        if (rawVal == UNINITIALIZED) {
            return NO_VALUE;
        }
        if (prop.getHook() != null && !prop.getHook().isAbstract()
                && !obj.inMagic(prop.name(), PhpObject.GUARD_HOOK_ANY)) {
            return unwrapProp(callHook(obj, prop, prop.getHook(), null, PhpObject.GUARD_HOOK_GET));
        }
        return unwrapProp(rawVal);
    }

    private static Object readForEnumeration(PhpObject obj, String name, Object rawVal) {
        InstanceProp prop = obj.phpClass().findVisibleProp(name);
        if (prop == null) {
            prop = obj.phpClass().findPrivateProp(name);
        }
        if (prop != null && prop.getHook() != null && !prop.getHook().isAbstract()
                && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
            return unwrapProp(callHook(obj, prop, prop.getHook(), null, PhpObject.GUARD_HOOK_GET));
        }
        return unwrapProp(rawVal);
    }

    /** Calls a hook on {@code obj}; {@code value} is the set hook's argument. */
    @TruffleBoundary
    public static Object callHook(PhpObject obj, InstanceProp prop, PropertyHook hook, Object value, int kind) {
        if (hook.callTarget() == null) {
            throw new PhpError("Cannot call abstract method " + hook.declaringClass().name()
                    + "::$" + prop.name() + "::" + hook.name() + "()");
        }
        // A nested hook call for the same property kind (parent::$x::set inside $x::set) must
        // not clear the outer guard on its way out: that guard is what makes the rest of the
        // outer hook body address the backing store.
        boolean already = obj.inMagic(prop.name(), kind);
        if (!already) {
            obj.enterMagic(prop.name(), kind);
        }
        try {
            Object[] args = new Object[hook.fromTrait() ? 4 : 3];
            args[0] = obj;
            args[1] = obj.phpClass();
            int at = 2;
            if (hook.fromTrait()) {
                args[at++] = hook.declaringClass();
            }
            if (kind == PhpObject.GUARD_HOOK_SET) {
                args[at] = value;
            } else {
                args = java.util.Arrays.copyOf(args, at);
            }
            return PhpBacktrace.callUnsited(hook.callTarget(), args);
        } finally {
            if (!already) {
                obj.exitMagic(prop.name(), kind);
            }
        }
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
        Set<String> handled = new HashSet<>();
        for (InstanceProp p : cls.instanceProps) {
            boolean visible;
            if (p.visibility() == Visibility.PUBLIC) {
                visible = true;
            } else if (p.visibility() == Visibility.PROTECTED) {
                visible = caller != null && (cls.isInstanceOf(caller) || caller.isInstanceOf(p.declaringClass()));
            } else {
                visible = callerLower != null && PhpStrings.equalsIgnoreCase(p.declaringClass().name(), callerLower);
            }
            if (!visible) {
                handled.add(p.storageKey());
                continue;
            }
            if (p.hasHooks() || p.virtual()) {
                throw new PhpError("Cannot create reference to property " + cls.name() + "::$" + p.name());
            }
            handled.add(p.storageKey());
            Object raw = get.execute(obj, p.storageKey(), PhpNull.INSTANCE);
            if (raw == UNINITIALIZED) {
                continue;
            }
            out.add(Map.entry(p.name(), ensurePropRef(obj, p.storageKey(), get, put)));
        }
        for (Object rawKey : keys) {
            String k = (String) rawKey;
            if (handled.contains(k)) {
                continue;
            }
            Object rawVal = get.execute(obj, k, PhpNull.INSTANCE);
            if (rawVal == UNINITIALIZED || rawVal == null) {
                continue;
            }
            if (k.startsWith("\0")) {
                int sep = k.indexOf('\0', 1);
                String declaring = k.substring(1, sep);
                String prop = k.substring(sep + 1);
                if (callerLower != null && PhpStrings.equalsIgnoreCase(declaring, callerLower)) {
                    out.add(Map.entry(prop, ensurePropRef(obj, k, get, put)));
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
            pv = ref.getValue();
        }
        if (pv instanceof PhpBacktrace.LazyTrace lt) {
            pv = lt.toPhpArray();
        }
        return pv == null ? PhpNull.INSTANCE : pv;
    }
}
