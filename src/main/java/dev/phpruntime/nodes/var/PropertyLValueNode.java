package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.DeclaredType;
import dev.phpruntime.runtime.CallMode;
import dev.phpruntime.runtime.Visibility;
import dev.phpruntime.runtime.SplArrayState;

/** {@code $obj->prop} or {@code $obj->$name} lvalue. */
public final class PropertyLValueNode extends LValueNode {

    @Child private PhpNode targetExpr;
    private final String staticName;
    @Child private PhpNode dynamicNameExpr;
    private final String callingClassLower;
    /** Frame slot holding the using class inside a trait method (the caller for visibility), else -1. */
    private final int traitSelfSlot;
    /** Bytecode headless copies take the using class from keys[2] instead of the frame. */
    private final boolean traitCallerInKeys;
    private final com.oracle.truffle.api.profiles.CountingConditionProfile arrayPropertyProfile =
            com.oracle.truffle.api.profiles.CountingConditionProfile.create();

    @Child private DynamicObject.GetNode getNode = DynamicObject.GetNode.create();
    @Child private DynamicObject.PutNode putNode = DynamicObject.PutNode.create();
    @Child private DynamicObject.RemoveKeyNode removeNode = DynamicObject.RemoveKeyNode.create();
    @Child private ArrayAccessNode arrayAccess;

    /**
     * How {@link #staticName} resolves for one class hierarchy. Published as a single immutable
     * object so a request reading it while another installs it never sees half an entry. The
     * nested resolution carries the hook targets a monomorphic site calls directly.
     */
    private record Resolution(long hierarchyId, long callerHierarchyId, PhpClass.PropertyResolution res) {
    }

    /** Most class hierarchies (or hierarchy and caller pairs) one site caches before going megamorphic. */
    private static final int CACHE_LIMIT = 8;
    private static final Resolution[] MEGAMORPHIC = new Resolution[0];

    /**
     * Resolutions seen at this site, replaced as a whole when one is added. A method of a base class
     * run on several subclasses (WP_Dependencies on WP_Styles and WP_Scripts) needs several.
     */
    @CompilationFinal(dimensions = 1) private Resolution[] cache;
    /** Language-wide class id of {@link #callingClassLower}, resolved on first use; -1 until then. */
    @CompilationFinal private int callerClassId = -1;

    public PropertyLValueNode(PhpNode targetExpr, String staticName, PhpNode dynamicNameExpr, String callingClassLower) {
        this(targetExpr, staticName, dynamicNameExpr, callingClassLower, -1, false);
    }

    public PropertyLValueNode(PhpNode targetExpr, String staticName, PhpNode dynamicNameExpr,
                              String callingClassLower, int traitSelfSlot) {
        this(targetExpr, staticName, dynamicNameExpr, callingClassLower, traitSelfSlot, false);
    }

    private PropertyLValueNode(PhpNode targetExpr, String staticName, PhpNode dynamicNameExpr,
                               String callingClassLower, int traitSelfSlot, boolean traitCallerInKeys) {
        this.traitSelfSlot = traitSelfSlot;
        this.traitCallerInKeys = traitCallerInKeys;
        this.targetExpr = targetExpr;
        this.staticName = staticName;
        this.dynamicNameExpr = dynamicNameExpr;
        this.callingClassLower = callingClassLower;
    }

    /** A copy without its expressions, for bytecode, which evaluates the object and the name. */
    public PropertyLValueNode headless() {
        return new PropertyLValueNode(null, staticName, null, callingClassLower, -1, traitSelfSlot >= 0).at(line());
    }

    public PhpNode targetExpr() {
        return targetExpr;
    }

    public String staticName() {
        return staticName;
    }

    public PhpNode dynamicNameExpr() {
        return dynamicNameExpr;
    }

    public boolean usesTraitSelf() {
        return traitSelfSlot >= 0 || traitCallerInKeys;
    }

    public int traitSelfSlot() {
        return traitSelfSlot;
    }

    /** The rvalue read with the receiver and name already evaluated. */
    public Object executeOn(VirtualFrame frame, Object target, String name) {
        return executeOn(frame, target, name, null);
    }

    /** The rvalue read with evaluated bytecode place keys (the trait caller is keys[2]). */
    public Object executeOn(VirtualFrame frame, Object target, String name, Object[] keys) {
        if (!(target instanceof PhpObject obj)) {
            warnNonObject(target, name);
            return PhpNull.INSTANCE;
        }
        if (arrayProperties(obj, name)) {
            return arrayAccess().get(obj, name);
        }
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            return readResolved(frame, obj, name, fast.res(), keys);
        }
        return readSlow(frame, obj, name, keys, true);
    }

    private Object readResolved(VirtualFrame frame, PhpObject obj, String name,
                                PhpClass.PropertyResolution res, Object[] keys) {
        if (!res.accessible()) {
            return readFallback(obj, name, true, true, effectiveCallerLower(frame, keys));
        }
        PhpClass.PropertyHook getHook = res.getHook();
        if (getHook != null && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
            return hookValue(obj, name, getHook);
        }
        if (res.virtual()) {
            throw cannotReadVirtual(obj, name, res.setHook() != null);
        }
        Object v = getNode.execute(obj, res.storageKey(), null);
        if (v == PhpClass.UNINITIALIZED) {
            return readUninitialized(obj, name, effectiveCallerLower(frame, keys));
        }
        if (v instanceof PhpReference ref) {
            v = ref.getValue();
        }
        if (v != null) {
            return v instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lt ? lt.toPhpArray() : v;
        }
        return readFallback(obj, name, false, true, effectiveCallerLower(frame, keys));
    }

    private Object readSlow(VirtualFrame frame, PhpObject obj, String name, Object[] keys, boolean warnMissing) {
        String callerLower = effectiveCallerLower(frame, keys);
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower);
        if (!res.accessible()) {
            return readFallback(obj, name, true, warnMissing, callerLower);
        }
        PhpClass.PropertyHook getHook = res.getHook();
        if (getHook != null && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
            return hookValue(obj, name, getHook);
        }
        if (res.virtual()) {
            throw cannotReadVirtual(obj, name, res.setHook() != null);
        }
        Object v = getNode.execute(obj, res.storageKey(), null);
        if (v == PhpClass.UNINITIALIZED) {
            return readUninitialized(obj, name, callerLower);
        }
        if (v instanceof PhpReference ref) {
            v = ref.getValue();
        }
        if (v != null) {
            return v instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lt ? lt.toPhpArray() : v;
        }
        return readFallback(obj, name, false, warnMissing, callerLower);
    }

    @TruffleBoundary
    private void warnNonObject(Object target, String name) {
        PhpContext.get(this).warning(
                "Attempt to read property " + '"' + name + '"' + " on " + PhpOps.typeName(target), line());
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        if (arrayProperties(obj, name)) {
            return arrayAccess().get(obj, name);
        }
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            if (!fast.res().accessible()) {
                return null;
            }
            PhpClass.PropertyResolution res = fast.res();
            PhpClass.PropertyHook getHook = res.getHook();
            if (getHook != null && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
                return hookValue(obj, name, getHook);
            }
            if (res.virtual()) {
                throw cannotReadVirtual(obj, name, res.setHook() != null);
            }
            Object v = getNode.execute(obj, res.storageKey(), null);
            if (v == PhpClass.UNINITIALIZED) {
                return readUninitialized(obj, name, effectiveCallerLower(frame, keys));
            }
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            if (v != null) {
                return v instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lt ? lt.toPhpArray() : v;
            }
            return readFallback(obj, name, false, false, effectiveCallerLower(frame, keys));
        }
        return readSlow(frame, obj, name, keys, false);
    }

    @Override
    public boolean isSet(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return false;
        }
        String name = (String) keys[1];
        if (arrayProperties(obj, name)) {
            return arrayAccess().isSet(obj, name);
        }
        PhpClass.PropertyResolution res = resolveFor(frame, obj, name, keys);
        if (res.accessible()) {
            PhpClass.PropertyHook getHook = res.getHook();
            if (getHook != null && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
                Object v = hookValue(obj, name, getHook);
                return v != PhpNull.INSTANCE;
            }
            if (res.virtual()) {
                throw cannotReadVirtual(obj, name, res.setHook() != null);
            }
            Object v = getNode.execute(obj, res.storageKey(), null);
            if (v == PhpClass.UNINITIALIZED) {
                return checkMagicIsset(obj, name);
            }
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            if (v != null) {
                return v != PhpNull.INSTANCE;
            }
        }
        return checkMagicIsset(obj, name);
    }

    /** Marker for "hooked property whose get hook returned nothing usable", distinct from null. */
    private static final Object NO_ISSET = new Object();

    private PhpClass.PropertyResolution resolveFor(VirtualFrame frame, PhpObject obj, String name, Object[] keys) {
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            return fast.res();
        }
        return obj.phpClass().resolveProperty(PhpContext.get(this), name, effectiveCallerLower(frame, keys));
    }

    private Object readIfSetInternal(VirtualFrame frame, PhpObject obj, String name, Object[] keys) {
        Resolution fast = fastResolution(frame, obj, name, keys);
        PhpClass.PropertyResolution res;
        String callerLower;
        if (fast != null) {
            res = fast.res();
            callerLower = effectiveCallerLower(frame, keys);
        } else {
            callerLower = effectiveCallerLower(frame, keys);
            res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower);
        }
        if (res.accessible()) {
            PhpClass.PropertyHook getHook = res.getHook();
            if (getHook != null && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
                return hookValue(obj, name, getHook);
            }
            if (res.virtual()) {
                throw cannotReadVirtual(obj, name, res.setHook() != null);
            }
            Object v = getNode.execute(obj, res.storageKey(), null);
            if (v == PhpClass.UNINITIALIZED) {
                if (checkMagicIsset(obj, name)) {
                    PhpClass.MethodEntry get = obj.phpClass().getMethod();
                    return magicUsable(get, obj, name, PhpObject.GUARD_GET)
                            ? callMagicGuarded(get, obj, name, PhpObject.GUARD_GET) : NO_ISSET;
                }
                return null;
            }
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            if (v != null) {
                return v;
            }
        }
        if (checkMagicIsset(obj, name)) {
            PhpClass.MethodEntry get = obj.phpClass().getMethod();
            return magicUsable(get, obj, name, PhpObject.GUARD_GET)
                    ? callMagicGuarded(get, obj, name, PhpObject.GUARD_GET) : NO_ISSET;
        }
        return null;
    }

    @Override
    public Object readIfSet(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        if (arrayProperties(obj, name)) {
            return arrayAccess().readIfSet(obj, name);
        }
        Object v = readIfSetInternal(frame, obj, name, keys);
        return v == NO_ISSET ? PhpNull.INSTANCE : v;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Attempt to assign property " + '"' + name + '"' + " on " + PhpOps.typeName(target), this);
        }
        if (arrayProperties(obj, name)) {
            arrayAccess().set(obj, name, value);
            return;
        }
        checkEnumDynamicProperty(obj, name);
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            writeResolved(obj, name, value, fast.res(), effectiveCallerLower(frame, keys));
            return;
        }
        writeDynamic(obj, name, value, effectiveCallerLower(frame, keys));
    }

    private void writeDynamic(PhpObject obj, String name, Object value, String callerLower) {
        writeResolved(obj, name, value, resolveFull(obj, name, callerLower), callerLower);
    }

    private void writeResolved(PhpObject obj, String name, Object value, PhpClass.PropertyResolution res,
                               String callerLower) {
        if (!res.accessible()) {
            writeFallback(obj, name, value, true, callerLower);
            return;
        }
        if (res.writeBlocked()) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("ReflectionException", "Cannot set read-only property "
                    + obj.phpClass().name() + "::$" + name, this);
        }
        if (res.isReadonly()) {
            String storageKey = res.storageKey();
            Object existing = getNode.execute(obj, storageKey, null);
            writeReadonly(obj, name, storageKey, existing, res.canInitReadonly(), res.declaringClass(), value);
            return;
        }
        if (!res.writable()) {
            throw cannotModifySet(obj, name, res.setVisibility(), res.setDeclaringClass(), callerLower, "modify");
        }
        PhpClass.PropertyHook setHook = res.setHook();
        if (setHook != null && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
            callHook(obj, name, setHook, value, PhpObject.GUARD_HOOK_SET);
            return;
        }
        if (res.virtual()) {
            throw cannotWriteVirtual(obj, name);
        }
        String storageKey = res.storageKey();
        value = checkType(res.type(), obj, name, res.declaringClass(), value);
        Object existing = getNode.execute(obj, storageKey, null);
        if (existing instanceof PhpReference ref) {
            ref.setValue(value);
            return;
        }
        if (existing != null || !magicUsable(obj.phpClass().setMethod(), obj, name, PhpObject.GUARD_SET)) {
            putNode.execute(obj, storageKey, value);
            return;
        }
        writeFallback(obj, name, value, false, callerLower);
    }

    /** Enum cases have no dynamic properties: a write to any name they do not declare fails. */
    @TruffleBoundary
    private void checkEnumDynamicProperty(PhpObject obj, String name) {
        if (obj.phpClass().template().isEnum() && !obj.phpClass().hasDeclaredProperty(name)) {
            throw new PhpError("Cannot create dynamic property " + obj.phpClass().name() + "::$" + name, this);
        }
    }

    private void writeReadonly(PhpObject obj, String name, String storageKey, Object existing,
                               boolean canInit, String declaringClass, Object value) {
        if ((existing == PhpClass.UNINITIALIZED || existing == null || obj.isCloning()) && canInit) {
            putNode.execute(obj, storageKey, value);
            return;
        }
        throw cannotModifyReadonly(obj, declaringClass, name);
    }

    @TruffleBoundary
    private PhpError cannotModifyReadonly(PhpObject obj, String declaringClass, String name) {
        String owner = declaringClass != null ? declaringClass : obj.phpClass().name();
        return new PhpError("Cannot modify readonly property " + owner + "::$" + name, this);
    }

    public void checkNotReadonlyForIndirectWrite(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return;
        }
        String name = (String) keys[1];
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            checkIndirect(obj, name, fast.res(), effectiveCallerLower(frame, keys));
            return;
        }
        checkNotReadonlyBoundary(obj, name, effectiveCallerLower(frame, keys));
    }

    @TruffleBoundary
    private void checkNotReadonlyBoundary(PhpObject obj, String name, String callerLower) {
        checkIndirect(obj, name, obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower), callerLower);
    }

    private void checkIndirect(PhpObject obj, String name, PhpClass.PropertyResolution res, String callerLower) {
        if (res.isReadonly()) {
            String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot indirectly modify readonly property " + owner + "::$" + name, this);
        }
        if (obj.inMagic(name, PhpObject.GUARD_HOOK_GET) || obj.inMagic(name, PhpObject.GUARD_HOOK_SET)) {
            return;
        }
        if (!res.writable()) {
            throw cannotModifySet(obj, name, res.setVisibility(), res.setDeclaringClass(), callerLower, "indirectly modify");
        }
        if (res.virtual() || res.hasHooks()) {
            PhpClass.PropertyHook getHook = res.getHook();
            if (getHook == null || !getHook.byRef()) {
                if (!res.virtual()) {
                    Object v = getNode.execute(obj, res.storageKey(), null);
                    if (v == PhpClass.UNINITIALIZED) {
                        String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                        CompilerDirectives.transferToInterpreter();
                        throw new PhpError("Typed property " + owner + "::$" + name
                                + " must not be accessed before initialization", this);
                    }
                }
                throw indirectModification(obj, name);
            }
        }
    }

    @TruffleBoundary
    private PhpError indirectModification(PhpObject obj, String name) {
        return new PhpError("Indirect modification of " + obj.phpClass().name() + "::$" + name
                + " is not allowed", this);
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Attempt to modify property " + '"' + name + '"' + " on " + PhpOps.typeName(target), this);
        }
        if (arrayProperties(obj, name)) {
            return arrayAccess().cell(obj, name);
        }
        checkEnumDynamicProperty(obj, name);
        checkNotReadonlyForIndirectWrite(frame, keys);
        PhpClass.PropertyResolution res = resolveCached(frame, obj, name, keys);
        if (!res.accessible()) {
            throw inaccessibleError(obj, name, effectiveCallerLower(frame, keys));
        }
        PhpClass.PropertyHook getHook = res.getHook();
        if (getHook != null && getHook.byRef() && !obj.inMagic(name, PhpObject.GUARD_HOOK_ANY)) {
            Object v = callHook(obj, name, getHook, null, PhpObject.GUARD_HOOK_GET);
            if (v instanceof PhpReference ref) {
                ref.ensureDefined();
                return ref;
            }
            return new PhpReference(v == null ? PhpNull.INSTANCE : v);
        }
        if (res.virtual()) {
            throw indirectModification(obj, name);
        }
        String storageKey = res.storageKey();
        Object existing = getNode.execute(obj, storageKey, null);
        if (existing == PhpClass.UNINITIALIZED) {
            if (res.type() != null && !res.type().acceptsNull()) {
                String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                throw new PhpError("Cannot access uninitialized non-nullable property " + owner + "::$" + name
                        + " by reference", this);
            }
            PhpReference ref = new PhpReference(PhpNull.INSTANCE);
            if (res.type() != null) {
                ref.setCellCheck(cellCheck(res, obj, name));
            }
            putNode.execute(obj, storageKey, ref);
            return ref;
        }
        if (existing instanceof PhpReference ref) {
            ref.ensureDefined();
            if (res.type() != null) {
                ref.setCellCheck(cellCheck(res, obj, name));
            }
            return ref;
        }
        if (existing == null && magicUsable(obj.phpClass().getMethod(), obj, name, PhpObject.GUARD_GET)) {
            Object val = callMagicGuarded(obj.phpClass().getMethod(), obj, name, PhpObject.GUARD_GET);
            return new PhpReference(PhpOps.copyOnAssign(val));
        }
        PhpReference ref = new PhpReference(existing == null ? PhpNull.INSTANCE : existing);
        if (res.type() != null) {
            ref.setCellCheck(cellCheck(res, obj, name));
        }
        putNode.execute(obj, storageKey, ref);
        return ref;
    }

    @Override
    public PhpReference indirectCell(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        if (arrayProperties(obj, name)) {
            return null;
        }
        PhpClass.PropertyResolution res = resolveCached(frame, obj, name, keys);
        if (!res.accessible() || !res.hasHooks()) {
            return null;
        }
        PhpClass.PropertyHook getHook = res.getHook();
        if (getHook == null || !getHook.byRef()) {
            return null;
        }
        Object v = callHook(obj, name, getHook, null, PhpObject.GUARD_HOOK_GET);
        return v instanceof PhpReference ref ? ref : null;
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Attempt to assign property " + '"' + name + '"' + " on " + PhpOps.typeName(target), this);
        }
        if (arrayProperties(obj, name)) {
            arrayAccess().bindCell(obj, name, cell);
            return;
        }
        checkEnumDynamicProperty(obj, name);
        checkNotReadonlyForIndirectWrite(frame, keys);
        PhpClass.PropertyResolution res = resolveCached(frame, obj, name, keys);
        if (!res.accessible()) {
            throw inaccessibleError(obj, name, effectiveCallerLower(frame, keys));
        }
        if (res.virtual() || res.hasHooks()) {
            throw indirectModification(obj, name);
        }
        String storageKey = res.storageKey();
        if (res.type() != null) {
            Object current = cell.getValue();
            Object checked = res.type().checkProperty(current, PhpContext.get(this), this, CallMode.strictAt(this),
                    selfClass(res), res.declaringClass(), name);
            if (checked != current) {
                cell.setValue(checked);
            }
            cell.setCellCheck(cellCheck(res, obj, name));
        }
        putNode.execute(obj, storageKey, cell);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Attempt to modify property " + '"' + name + '"' + " on " + PhpOps.typeName(target), this);
        }
        if (arrayProperties(obj, name)) {
            arrayAccess().unset(obj, name);
            return;
        }
        PhpClass.PropertyResolution res = resolveCached(frame, obj, name, keys);
        if (res.hasHooks()) {
            throw new PhpError("Cannot unset hooked property " + obj.phpClass().name() + "::$" + name, this);
        }
        if (res.accessible() && res.isReadonly()) {
            Object existing = res.storageKey() == null ? null : getNode.execute(obj, res.storageKey(), null);
            if (existing != PhpClass.UNINITIALIZED || !res.canInitReadonly()) {
                String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot unset readonly property " + owner + "::$" + name, this);
            }
        }
        if (res.accessible() && !res.writable()) {
            throw cannotModifySet(obj, name, res.setVisibility(), res.setDeclaringClass(),
                    effectiveCallerLower(frame, keys), "unset");
        }
        String storageKey = res.accessible() ? res.storageKey() : null;
        if (storageKey != null && getNode.execute(obj, storageKey, null) != null) {
            if (res.isReadonly() || res.type() != null) {
                putNode.execute(obj, storageKey, PhpClass.UNINITIALIZED);
            } else {
                removeNode.execute(obj, storageKey);
            }
            return;
        }
        unsetFallback(obj, name, storageKey == null, effectiveCallerLower(frame, keys));
    }

    private PhpClass selfClass(PhpClass.PropertyResolution res) {
        String owner = res.declaringClass();
        return owner == null ? null : PhpContext.get(this).lookupClass(owner);
    }

    private Object checkType(DeclaredType type, PhpObject obj, String name, String owner, Object value) {
        if (type == null) {
            return value;
        }
        return type.checkProperty(value, PhpContext.get(this), this, CallMode.strictAt(this),
                owner == null ? null : PhpContext.get(this).lookupClass(owner),
                owner != null ? owner : obj.phpClass().name(), name);
    }

    private PhpReference.CellCheck cellCheck(PhpClass.PropertyResolution res, PhpObject obj, String name) {
        String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
        return res.type().propertyCellCheck(PhpContext.get(this), CallMode.strictAt(this), selfClass(res), owner, name);
    }

    @TruffleBoundary
    private Object readUninitialized(PhpObject obj, String name, String callerLower) {
        PhpClass.MethodEntry get = obj.phpClass().getMethod();
        if (magicUsable(get, obj, name, PhpObject.GUARD_GET)) {
            return callMagicGuarded(get, obj, name, PhpObject.GUARD_GET);
        }
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower);
        String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Typed property " + owner + "::$" + name + " must not be accessed before initialization", this);
    }

    /**
     * The site's cached resolution when it has one, else the full lookup. Visibility is only
     * approximate on the cached path; callers rebuild it for errors through inaccessibleError.
     */
    private PhpClass.PropertyResolution resolveCached(VirtualFrame frame, PhpObject obj, String name, Object[] keys) {
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            return fast.res();
        }
        return resolveFull(obj, name, effectiveCallerLower(frame, keys));
    }

    @TruffleBoundary
    private PhpClass.PropertyResolution resolveFull(PhpObject obj, String name, String callerLower) {
        return obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower);
    }

    @TruffleBoundary
    private Object readFallback(PhpObject obj, String name, boolean inaccessible, boolean warnMissing, String callerLower) {
        PhpClass.MethodEntry get = obj.phpClass().getMethod();
        if (magicUsable(get, obj, name, PhpObject.GUARD_GET)) {
            return callMagicGuarded(get, obj, name, PhpObject.GUARD_GET);
        }
        if (inaccessible) {
            throw inaccessibleError(obj, name, callerLower);
        }
        if (warnMissing) {
            PhpContext.get(this).warning("Undefined property: " + obj.phpClass().name() + "::$" + name, line());
            return PhpNull.INSTANCE;
        }
        return null;
    }

    @TruffleBoundary
    private boolean checkMagicIsset(PhpObject obj, String name) {
        PhpClass.MethodEntry isset = obj.phpClass().issetMethod();
        if (magicUsable(isset, obj, name, PhpObject.GUARD_ISSET)) {
            return PhpOps.toBool(callMagicGuarded(isset, obj, name, PhpObject.GUARD_ISSET));
        }
        return false;
    }

    /** A magic method applies unless it is already running for this property (php's guards). */
    @TruffleBoundary
    private static boolean magicUsable(PhpClass.MethodEntry entry, PhpObject obj, String name, int kind) {
        return entry != null && entry.callTarget() != null && !obj.inMagic(name, kind);
    }

    @TruffleBoundary
    private Object callMagicGuarded(PhpClass.MethodEntry entry, PhpObject obj, String name, int kind) {
        obj.enterMagic(name, kind);
        try {
            return callMagic1(entry, obj, name);
        } finally {
            obj.exitMagic(name, kind);
        }
    }

    @TruffleBoundary
    private Object callMagic1(PhpClass.MethodEntry entry, PhpObject obj, String name) {
        if (entry.fromTrait()) {
            return entry.callTarget().call(obj, obj.phpClass(), entry.declaringClass(), name);
        }
        return entry.callTarget().call(obj, obj.phpClass(), name);
    }

    @TruffleBoundary
    private void writeFallback(PhpObject obj, String name, Object value, boolean inaccessible, String callerLower) {
        PhpClass.MethodEntry set = obj.phpClass().setMethod();
        if (magicUsable(set, obj, name, PhpObject.GUARD_SET)) {
            obj.enterMagic(name, PhpObject.GUARD_SET);
            try {
                if (set.fromTrait()) {
                    set.callTarget().call(obj, obj.phpClass(), set.declaringClass(), name, value);
                } else {
                    set.callTarget().call(obj, obj.phpClass(), name, value);
                }
            } finally {
                obj.exitMagic(name, PhpObject.GUARD_SET);
            }
            return;
        }
        if (inaccessible) {
            throw inaccessibleError(obj, name, callerLower);
        }
    }

    @TruffleBoundary
    private void unsetFallback(PhpObject obj, String name, boolean inaccessible, String callerLower) {
        PhpClass.MethodEntry unset = obj.phpClass().unsetMethod();
        if (magicUsable(unset, obj, name, PhpObject.GUARD_UNSET)) {
            callMagicGuarded(unset, obj, name, PhpObject.GUARD_UNSET);
            return;
        }
        if (inaccessible) {
            throw inaccessibleError(obj, name, callerLower);
        }
    }

    private Resolution fastResolution(VirtualFrame frame, PhpObject obj, String name, Object[] keys) {
        if (staticName != null) {
            PhpClass cls = obj.phpClass();
            long h = cls.hierarchyId();
            Resolution[] entries = cache;
            if (entries == MEGAMORPHIC) {
                return null;
            }
            Resolution hit = lookup(entries, h, frame, keys);
            if (hit != null) {
                return hit;
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cacheResolution(cls, h, effectiveCallerLower(frame, keys), callerHierarchyId(frame, keys));
            return lookup(cache, h, frame, keys);
        }
        return null;
    }

    @ExplodeLoop
    private Resolution lookup(Resolution[] entries, long h, VirtualFrame frame, Object[] keys) {
        if (entries == null) {
            return null;
        }
        for (int i = 0; i < entries.length; i++) {
            Resolution c = entries[i];
            if (h == c.hierarchyId() && (!callerSensitive(c) || c.callerHierarchyId() == callerHierarchyId(frame, keys))) {
                return c;
            }
        }
        return null;
    }

    private String resolveKeyIfAccessible(VirtualFrame frame, PhpObject obj, String name, Object[] keys) {
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            return fast.res().accessible() ? fast.res().storageKey() : null;
        }
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name,
                effectiveCallerLower(frame, keys));
        return res.accessible() ? res.storageKey() : null;
    }

    @TruffleBoundary
    private PhpError inaccessibleError(PhpObject obj, String name, String callerLower) {
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower);
        return inaccessibleError(res.visibility(), res.declaringClass(), name);
    }

    @TruffleBoundary
    private PhpClass.PropertyResolution cacheResolution(PhpClass cls, long h, String callerLower, long callerH) {
        PhpContext ctx = PhpContext.get(this);
        PhpClass.PropertyResolution res = cls.resolveProperty(ctx, staticName, callerLower);
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            Resolution[] entries = cache;
            if (entries != MEGAMORPHIC) {
                Resolution entry = new Resolution(h, callerH, res);
                boolean present = false;
                if (entries != null) {
                    for (Resolution c : entries) {
                        present |= c.hierarchyId() == h && (!callerSensitive(c) || c.callerHierarchyId() == callerH);
                    }
                }
                if (!present) {
                    Resolution[] next;
                    if (entries == null) {
                        next = new Resolution[]{entry};
                    } else if (entries.length >= CACHE_LIMIT) {
                        next = MEGAMORPHIC;
                    } else {
                        next = java.util.Arrays.copyOf(entries, entries.length + 1);
                        next[entries.length] = entry;
                    }
                    // Readers take the array without the lock: its elements must be visible first.
                    java.lang.invoke.VarHandle.storeStoreFence();
                    cache = next;
                }
            }
        } finally {
            lock.unlock();
        }
        return res;
    }

    /** Whether a cached resolution depends on the caller (inside a trait method it varies by using class). */
    private boolean callerSensitive(Resolution c) {
        return traitSelfSlot >= 0 || traitCallerInKeys || c.res().visibility() != Visibility.PUBLIC
                || c.res().setVisibility() != Visibility.PUBLIC || c.res().isReadonly()
                || c.res().getHook() != null || c.res().setHook() != null;
    }

    private String effectiveCallerLower(VirtualFrame frame, Object[] keys) {
        if (traitCallerInKeys) {
            PhpClass c = keys != null && keys.length > 2 && keys[2] instanceof PhpClass pc ? pc : null;
            return c != null ? c.template().lowerName() : null;
        }
        if (traitSelfSlot >= 0) {
            PhpClass c = (PhpClass) frame.getObject(traitSelfSlot);
            return c != null ? c.template().lowerName() : null;
        }
        return callingClassLower;
    }

    private boolean arrayProperties(PhpObject obj, String name) {
        if (arrayPropertyProfile.profile(obj.nativeState() instanceof SplArrayState state
                && (state.flags & SplArrayState.ARRAY_AS_PROPS) != 0)) {
            return !obj.phpClass().hasDeclaredProperty(name);
        }
        return false;
    }

    private ArrayAccessNode arrayAccess() {
        ArrayAccessNode access = arrayAccess;
        if (access != null) { return access; }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (arrayAccess == null) {
                arrayAccess = insert(new ArrayAccessNode(dev.phpruntime.PhpLanguage.get(this)));
            }
            return arrayAccess;
        } finally {
            lock.unlock();
        }
    }

    private long callerHierarchyId(VirtualFrame frame, Object[] keys) {
        if (traitCallerInKeys) {
            PhpClass c = keys != null && keys.length > 2 && keys[2] instanceof PhpClass pc ? pc : null;
            return c != null ? c.hierarchyId() : -1;
        }
        if (traitSelfSlot >= 0) {
            PhpClass c = (PhpClass) frame.getObject(traitSelfSlot);
            return c != null ? c.hierarchyId() : -1;
        }
        if (callingClassLower == null) {
            return -1;
        }
        int id = callerClassId;
        if (id < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            id = dev.phpruntime.PhpLanguage.get(this).classId(callingClassLower);
            callerClassId = id;
        }
        // An array read in this request's class table instead of a name lookup behind a boundary.
        PhpClass caller = PhpContext.get(this).classById(id);
        return caller != null ? caller.hierarchyId() : -1;
    }

    private PhpError inaccessibleError(Visibility vis, String declaringClass, String name) {
        String level = vis == Visibility.PRIVATE ? "private" : "protected";
        return new PhpError("Cannot access " + level + " property " + declaringClass + "::$" + name, this);
    }

    private Object hookValue(PhpObject obj, String name, PhpClass.PropertyHook hook) {
        Object v = callHook(obj, name, hook, null, PhpObject.GUARD_HOOK_GET);
        if (v instanceof PhpReference ref) {
            v = ref.getValue();
        }
        return v == null ? PhpNull.INSTANCE : v;
    }

    @TruffleBoundary
    private Object callHook(PhpObject obj, String name, PhpClass.PropertyHook hook, Object value, int kind) {
        PhpClass.InstanceProp prop = obj.phpClass().findVisibleProp(name);
        return PhpClass.callHook(obj, prop != null ? prop : obj.phpClass().findPrivateProp(name),
                hook, value, kind);
    }

    @TruffleBoundary
    private PhpError cannotReadVirtual(PhpObject obj, String name, boolean setOnly) {
        return new PhpError("Cannot read from set-only virtual property " + obj.phpClass().name()
                + "::$" + name, this);
    }

    @TruffleBoundary
    private PhpError cannotWriteVirtual(PhpObject obj, String name) {
        return new PhpError("Cannot write to get-only virtual property " + obj.phpClass().name()
                + "::$" + name, this);
    }

    @TruffleBoundary
    private PhpError cannotModifySet(PhpObject obj, String name, Visibility setVisibility,
                                     String setDeclaringClass, String callerLower, String action) {
        String level = setVisibility == Visibility.PRIVATE ? "private(set)" : "protected(set)";
        String owner = setDeclaringClass != null ? setDeclaringClass : obj.phpClass().name();
        PhpContext ctx = PhpContext.get(this);
        return new PhpError("Cannot " + action + " " + level + " property " + owner + "::$" + name
                + " from " + PhpClass.scopeName(ctx, callerLower), this);
    }

    @Override
    public String describe() {
        return staticName != null ? "$obj->" + staticName : "$obj->$prop";
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.place(this, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ, 0, null);
    }
}
