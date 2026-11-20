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
     * object so a request reading it while another installs it never sees half an entry.
     */
    private record Resolution(long hierarchyId, long callerHierarchyId, String storageKey,
                              boolean isProtected, boolean accessible, boolean isReadonly,
                              boolean canInitReadonly, String declaringClass, DeclaredType type) {
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
        String storageKey = resolveKeyIfAccessible(frame, obj, name, keys);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
            if (v == PhpClass.UNINITIALIZED) {
                return readUninitialized(obj, name, effectiveCallerLower(frame, keys));
            }
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            if (v != null) {
                return v instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lt ? lt.toPhpArray() : v;
            }
        }
        return readFallback(obj, name, storageKey == null, true, effectiveCallerLower(frame, keys));
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
        String storageKey = resolveKeyIfAccessible(frame, obj, name, keys);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
            if (v == PhpClass.UNINITIALIZED) {
                return readUninitialized(obj, name, effectiveCallerLower(frame, keys));
            }
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            if (v != null) {
                return v instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lt ? lt.toPhpArray() : v;
            }
        }
        return readFallback(obj, name, storageKey == null, false, effectiveCallerLower(frame, keys));
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
        String storageKey = resolveKeyIfAccessible(frame, obj, name, keys);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
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

    @Override
    public Object readIfSet(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        if (arrayProperties(obj, name)) {
            return arrayAccess().readIfSet(obj, name);
        }
        String storageKey = resolveKeyIfAccessible(frame, obj, name, keys);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
            if (v == PhpClass.UNINITIALIZED) {
                if (checkMagicIsset(obj, name)) {
                    PhpClass.MethodEntry get = obj.phpClass().getMethod();
                    return magicUsable(get, obj, name, PhpObject.GUARD_GET) ? callMagicGuarded(get, obj, name, PhpObject.GUARD_GET) : PhpNull.INSTANCE;
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
            return magicUsable(get, obj, name, PhpObject.GUARD_GET) ? callMagicGuarded(get, obj, name, PhpObject.GUARD_GET) : PhpNull.INSTANCE;
        }
        return null;
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
        Resolution fast = fastResolution(frame, obj, name, keys);
        if (fast != null) {
            if (!fast.accessible()) {
                writeFallback(obj, name, value, true, effectiveCallerLower(frame, keys));
                return;
            }
            String storageKey = fast.storageKey();
            value = checkType(fast.type(), obj, name, fast.declaringClass(), value);
            Object existing = getNode.execute(obj, storageKey, null);
            if (fast.isReadonly()) {
                writeReadonly(obj, name, storageKey, existing, fast.canInitReadonly(), fast.declaringClass(), value);
                return;
            }
            if (existing instanceof PhpReference ref) {
                ref.setValue(value);
                return;
            }
            if (existing != null || !magicUsable(obj.phpClass().setMethod(), obj, name, PhpObject.GUARD_SET)) {
                putNode.execute(obj, storageKey, value);
                return;
            }
            writeFallback(obj, name, value, false, effectiveCallerLower(frame, keys));
            return;
        }
        writeDynamic(obj, name, value, effectiveCallerLower(frame, keys));
    }

    private void writeDynamic(PhpObject obj, String name, Object value, String callerLower) {
        PhpClass.PropertyResolution res = resolveFull(obj, name, callerLower);
        if (!res.accessible()) {
            writeFallback(obj, name, value, true, callerLower);
            return;
        }
        String storageKey = res.storageKey();
        value = checkType(res.type(), obj, name, res.declaringClass(), value);
        Object existing = getNode.execute(obj, storageKey, null);
        if (res.isReadonly()) {
            writeReadonly(obj, name, storageKey, existing, res.canInitReadonly(), res.declaringClass(), value);
            return;
        }
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
            if (fast.isReadonly()) {
                String owner = fast.declaringClass() != null ? fast.declaringClass() : obj.phpClass().name();
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot indirectly modify readonly property " + owner + "::$" + name, this);
            }
            return;
        }
        checkNotReadonlyBoundary(obj, name, effectiveCallerLower(frame, keys));
    }

    @TruffleBoundary
    private void checkNotReadonlyBoundary(PhpObject obj, String name, String callerLower) {
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower);
        if (res.isReadonly()) {
            String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot indirectly modify readonly property " + owner + "::$" + name, this);
        }
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
        checkNotReadonlyForIndirectWrite(frame, keys);
        PhpClass.PropertyResolution res = resolveFull(obj, name, effectiveCallerLower(frame, keys));
        if (!res.accessible()) {
            throw inaccessibleError(obj, name, effectiveCallerLower(frame, keys));
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
        checkNotReadonlyForIndirectWrite(frame, keys);
        String callerLower = effectiveCallerLower(frame, keys);
        PhpClass.PropertyResolution res = resolveFull(obj, name, callerLower);
        if (!res.accessible()) {
            throw inaccessibleError(obj, name, callerLower);
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
        PhpClass.PropertyResolution res = resolveFull(obj, name, effectiveCallerLower(frame, keys));
        if (res.accessible() && res.isReadonly()) {
            Object existing = getNode.execute(obj, res.storageKey(), null);
            if (existing != PhpClass.UNINITIALIZED || !res.canInitReadonly()) {
                String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot unset readonly property " + owner + "::$" + name, this);
            }
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
            return fast.accessible() ? fast.storageKey() : null;
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
                Resolution entry = new Resolution(h, callerH, res.storageKey(), res.visibility() == Visibility.PROTECTED,
                        res.accessible(), res.isReadonly(), res.canInitReadonly(), res.declaringClass(), res.type());
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
        return traitSelfSlot >= 0 || traitCallerInKeys || c.isProtected() || c.isReadonly();
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

    @Override
    public String describe() {
        return staticName != null ? "$obj->" + staticName : "$obj->$prop";
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.place(this, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ, 0, null);
    }
}
