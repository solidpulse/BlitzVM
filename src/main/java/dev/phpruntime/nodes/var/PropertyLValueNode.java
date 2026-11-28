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

    @Child private DynamicObject.GetNode getNode = DynamicObject.GetNode.create();
    @Child private DynamicObject.PutNode putNode = DynamicObject.PutNode.create();
    @Child private DynamicObject.RemoveKeyNode removeNode = DynamicObject.RemoveKeyNode.create();
    @Child private ArrayAccessNode arrayAccess;

    /**
     * How {@link #staticName} resolves for one class hierarchy. Published as a single immutable
     * object so a request reading it while another installs it never sees half an entry. The
     * nested resolution carries the hook targets a monomorphic site calls directly, and the
     * booleans that would otherwise be recomputed from it on every access.
     */
    private record Resolution(long hierarchyId, long callerHierarchyId, boolean callerSensitive,
                              boolean arrayProps, PhpClass.PropertyResolution res) {
        static Resolution of(long hierarchyId, long callerHierarchyId, PhpClass cls, PhpClass.PropertyResolution res,
                             boolean traitCaller) {
            boolean sensitive = traitCaller || res.visibility() != Visibility.PUBLIC
                    || res.setVisibility() != Visibility.PUBLIC || res.isReadonly()
                    || res.getHook() != null || res.setHook() != null;
            return new Resolution(hierarchyId, callerHierarchyId, sensitive, cls.mayHaveSplArrayState(), res);
        }
    }

    /** Most class hierarchies (or hierarchy and caller pairs) one site caches before going megamorphic. */
    private static final int CACHE_LIMIT = 8;
    private static final Resolution[] MEGAMORPHIC = new Resolution[0];

    /**
     * Resolutions seen at this site, replaced as a whole when one is added. A method of a base class
     * run on several subclasses (WP_Dependencies on WP_Styles and WP_Scripts) needs several.
     */
    @CompilationFinal(dimensions = 1) private Resolution[] cache;
    /**
     * The most recently installed entry, tried before the array: one operation on an object
     * (a {@code $this->items[] = v} write, for example) resolves the same property several
     * times, and the repeats then cost a field compare instead of an array scan.
     */
    @CompilationFinal private Resolution lastResolution;
    /** Language-wide class id of {@link #callingClassLower}, resolved on first use; -1 until then. */
    @CompilationFinal private int callerClassId = -1;
    /** The caller class's hierarchy across contexts, looked up once ({@link #callerStableLooked}). */
    @CompilationFinal private dev.phpruntime.PhpLanguage.StableHierarchy callerStable;
    @CompilationFinal private boolean callerStableLooked;
    /**
     * Stored-value shapes this site has seen. A plain value carries none of them, so until one
     * appears the compiled read is a field load and a null check; the first reference, append
     * buffer or lazy backtrace deoptimizes once and adds the branch for good.
     */
    @CompilationFinal private boolean refSeen;
    @CompilationFinal private boolean bufferSeen;
    @CompilationFinal private boolean lazySeen;
    /** A write through a stored reference; plain declared properties never have one. */
    @CompilationFinal private boolean refWriteSeen;

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

    public Object executeOn(VirtualFrame frame, Object target, String name, Object[] keys) {
        return executeOn(frame, target, name, keys, PhpContext.get(this));
    }

    /** The rvalue read with evaluated bytecode place keys (the trait caller is keys[2]). */
    public Object executeOn(VirtualFrame frame, Object target, String name, Object[] keys, PhpContext ctx) {
        if (!(target instanceof PhpObject obj)) {
            warnNonObject(target, name);
            return PhpNull.INSTANCE;
        }
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null) {
            if (fast.arrayProps() && arrayProperties(obj, name)) {
                return arrayAccess().get(obj, name);
            }
            return readResolved(frame, obj, name, fast.res(), keys, ctx);
        }
        if (arrayProperties(obj, name)) {
            return arrayAccess().get(obj, name);
        }
        return readSlow(frame, obj, name, keys, true, ctx);
    }

    private Object readResolved(VirtualFrame frame, PhpObject obj, String name,
                                PhpClass.PropertyResolution res, Object[] keys, PhpContext ctx) {
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
            return readUninitialized(obj, name, effectiveCallerLower(frame, keys), ctx);
        }
        v = unwrapStored(v);
        if (v != null) {
            return v;
        }
        return readFallback(obj, name, false, true, null);
    }

    private Object readSlow(VirtualFrame frame, PhpObject obj, String name, Object[] keys, boolean warnMissing,
                            PhpContext ctx) {
        String callerLower = effectiveCallerLower(frame, keys);
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(ctx, name, callerLower);
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
            return readUninitialized(obj, name, effectiveCallerLower(frame, keys), ctx);
        }
        v = unwrapStored(v);
        if (v != null) {
            return v;
        }
        return readFallback(obj, name, false, warnMissing, callerLower);
    }

    /**
     * A stored value as a read exposes it. Ordinary values come back unchanged; a reference, an
     * append buffer and a lazy backtrace are each seen at most once before the site adds their
     * branch, so compiled reads of a plain stored value contain none of them.
     */
    private Object unwrapStored(Object v) {
        if (v instanceof PhpReference ref) {
            if (!refSeen) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                refSeen = true;
            }
            v = ref.rawValue();
            if (v instanceof dev.phpruntime.runtime.PhpStringBuffer buffer) {
                if (!bufferSeen) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    bufferSeen = true;
                }
                v = buffer.asString();
            }
        }
        if (v instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lt) {
            if (!lazySeen) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                lazySeen = true;
            }
            v = lt.toPhpArray();
        }
        return v;
    }

    @TruffleBoundary
    private void warnNonObject(Object target, String name) {
        PhpContext.get(this).warning(
                "Attempt to read property " + '"' + name + '"' + " on " + PhpOps.typeName(target), line());
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        return read(frame, keys, PhpContext.get(this));
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null) {
            if (fast.arrayProps() && arrayProperties(obj, name)) {
                return arrayAccess().get(obj, name);
            }
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
                return readUninitialized(obj, name, effectiveCallerLower(frame, keys), ctx);
            }
            v = unwrapStored(v);
            if (v != null) {
                return v;
            }
            return readFallback(obj, name, false, false, null);
        }
        if (arrayProperties(obj, name)) {
            return arrayAccess().get(obj, name);
        }
        return readSlow(frame, obj, name, keys, false, ctx);
    }

    @Override
    public boolean isSet(VirtualFrame frame, Object[] keys) {
        return isSet(frame, keys, PhpContext.get(this));
    }

    @Override
    public boolean isSet(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return false;
        }
        String name = (String) keys[1];
        PhpClass.PropertyResolution res;
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null) {
            if (fast.arrayProps() && arrayProperties(obj, name)) {
                return arrayAccess().isSet(obj, name);
            }
            res = fast.res();
        } else {
            if (arrayProperties(obj, name)) {
                return arrayAccess().isSet(obj, name);
            }
            res = obj.phpClass().resolveProperty(ctx, name, effectiveCallerLower(frame, keys));
        }
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
            v = unwrapStored(v);
            if (v != null) {
                return v != PhpNull.INSTANCE;
            }
        }
        return checkMagicIsset(obj, name);
    }

    /** Marker for "hooked property whose get hook returned nothing usable", distinct from null. */
    private static final Object NO_ISSET = new Object();

    private Object readIfSetInternal(VirtualFrame frame, PhpObject obj, String name, Object[] keys, Resolution fast,
                                     PhpContext ctx) {
        PhpClass.PropertyResolution res;
        String callerLower;
        if (fast != null) {
            res = fast.res();
            callerLower = null;
        } else {
            callerLower = effectiveCallerLower(frame, keys);
            res = obj.phpClass().resolveProperty(ctx, name, callerLower);
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
            v = unwrapStored(v);
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
        return readIfSet(frame, keys, PhpContext.get(this));
    }

    @Override
    public Object readIfSet(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null ? fast.arrayProps() : true) {
            if (arrayProperties(obj, name)) {
                return arrayAccess().readIfSet(obj, name);
            }
        }
        Object v = readIfSetInternal(frame, obj, name, keys, fast, ctx);
        return v == NO_ISSET ? PhpNull.INSTANCE : v;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        write(frame, keys, value, PhpContext.get(this));
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value, PhpContext ctx) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError(msg("Attempt to assign property \"", name, "\" on ", PhpOps.typeName(target)), this);
        }
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null ? fast.arrayProps() : true) {
            if (arrayProperties(obj, name)) {
                arrayAccess().set(obj, name, value);
                return;
            }
        }
        checkEnumDynamicProperty(obj, name);
        if (fast != null) {
            writeResolved(obj, name, value, fast.res(), frame, keys, ctx);
            return;
        }
        writeDynamic(obj, name, value, frame, keys, ctx);
    }

    private void writeDynamic(PhpObject obj, String name, Object value, VirtualFrame frame, Object[] keys,
                              PhpContext ctx) {
        writeResolved(obj, name, value, resolveFull(obj, name, effectiveCallerLower(frame, keys), ctx), frame, keys, ctx);
    }

    private void writeResolved(PhpObject obj, String name, Object value, PhpClass.PropertyResolution res,
                               VirtualFrame frame, Object[] keys, PhpContext ctx) {
        if (!res.accessible()) {
            writeFallback(obj, name, value, true, effectiveCallerLower(frame, keys));
            return;
        }
        if (res.writeBlocked()) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("ReflectionException", msg("Cannot set read-only property ", obj.phpClass().name(), "::$", name), this);
        }
        if (res.isReadonly()) {
            String storageKey = res.storageKey();
            Object existing = getNode.execute(obj, storageKey, null);
            writeReadonly(obj, name, storageKey, existing, res.canInitReadonly(), res.declaringClass(), value);
            return;
        }
        if (!res.writable()) {
            throw cannotModifySet(obj, name, res.setVisibility(), res.setDeclaringClass(),
                    effectiveCallerLower(frame, keys), "modify");
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
        value = checkType(res.type(), obj, name, res.declaringClass(), value, ctx);
        Object existing = getNode.execute(obj, storageKey, null);
        if (existing instanceof PhpReference ref) {
            if (!refWriteSeen) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                refWriteSeen = true;
            }
            ref.setValue(value);
            return;
        }
        if (existing != null || !magicUsable(obj.phpClass().setMethod(), obj, name, PhpObject.GUARD_SET)) {
            putNode.execute(obj, storageKey, value);
            return;
        }
        writeFallback(obj, name, value, false, null);
    }

    /**
     * Enum cases have no dynamic properties: a write to any name they do not declare fails. The
     * enum test is two field reads and stays in compiled code; only enums take the boundary.
     */
    private void checkEnumDynamicProperty(PhpObject obj, String name) {
        if (obj.phpClass().template().isEnum()) {
            checkEnumProperty(obj, name);
        }
    }

    @TruffleBoundary
    private void checkEnumProperty(PhpObject obj, String name) {
        if (!obj.phpClass().hasDeclaredProperty(name)) {
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
        checkNotReadonlyForIndirectWrite(frame, keys, PhpContext.get(this));
    }

    public void checkNotReadonlyForIndirectWrite(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return;
        }
        String name = (String) keys[1];
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null) {
            checkIndirect(obj, name, fast.res(), frame, keys);
            return;
        }
        checkNotReadonlySlow(obj, name, effectiveCallerLower(frame, keys), frame, keys, ctx);
    }

    /**
     * The slow path for a site with no cached resolution: the cache miss already paid for the
     * caller, so it is passed in. Ordinary accesses never reach this.
     */
    private void checkNotReadonlySlow(PhpObject obj, String name, String callerLower,
                                      VirtualFrame frame, Object[] keys, PhpContext ctx) {
        checkIndirect(obj, name, resolveFull(obj, name, callerLower, ctx), frame, keys);
    }

    private void checkIndirect(PhpObject obj, String name, PhpClass.PropertyResolution res,
                               VirtualFrame frame, Object[] keys) {
        if (res.isReadonly()) {
            String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot indirectly modify readonly property " + owner + "::$" + name, this);
        }
        // Only a hooked or virtual property can have its own hook running; a plain property has
        // no guard to honour, so the resolution decides the rest without touching the object.
        boolean hooked = res.virtual() || res.hasHooks();
        if (hooked && (obj.inMagic(name, PhpObject.GUARD_HOOK_GET) || obj.inMagic(name, PhpObject.GUARD_HOOK_SET))) {
            return;
        }
        if (!res.writable()) {
            throw cannotModifySet(obj, name, res.setVisibility(), res.setDeclaringClass(),
                    effectiveCallerLower(frame, keys), "indirectly modify");
        }
        if (hooked) {
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

    @TruffleBoundary
    private PhpError modifyNonObject(String name, Object target) {
        return new PhpError("Attempt to modify property " + '"' + name + '"' + " on " + PhpOps.typeName(target), this);
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        return obtainCell(frame, keys, PhpContext.get(this));
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw modifyNonObject(name, target);
        }
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null ? fast.arrayProps() : true) {
            if (arrayProperties(obj, name)) {
                return arrayAccess().cell(obj, name);
            }
        }
        checkEnumDynamicProperty(obj, name);
        PhpClass.PropertyResolution res = fast != null ? fast.res()
                : resolveFull(obj, name, effectiveCallerLower(frame, keys), ctx);
        checkIndirect(obj, name, res, frame, keys);
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
                throw new PhpError(msg("Cannot access uninitialized non-nullable property ", owner, "::$", name, " by reference"), this);
            }
            PhpReference ref = new PhpReference(PhpNull.INSTANCE);
            if (res.type() != null) {
                ref.setCellCheck(cellCheck(res, obj, name, ctx));
            }
            putNode.execute(obj, storageKey, ref);
            return ref;
        }
        if (existing instanceof PhpReference ref) {
            ref.ensureDefined();
            if (res.type() != null) {
                ref.setCellCheck(cellCheck(res, obj, name, ctx));
            }
            return ref;
        }
        if (existing == null && magicUsable(obj.phpClass().getMethod(), obj, name, PhpObject.GUARD_GET)) {
            Object val = callMagicGuarded(obj.phpClass().getMethod(), obj, name, PhpObject.GUARD_GET);
            return new PhpReference(PhpOps.copyOnAssign(val));
        }
        PhpReference ref = new PhpReference(existing == null ? PhpNull.INSTANCE : existing);
        if (res.type() != null) {
            ref.setCellCheck(cellCheck(res, obj, name, ctx));
        }
        putNode.execute(obj, storageKey, ref);
        return ref;
    }

    @Override
    public PhpReference indirectCell(VirtualFrame frame, Object[] keys) {
        return indirectCell(frame, keys, PhpContext.get(this));
    }

    @Override
    public PhpReference indirectCell(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null ? fast.arrayProps() : true) {
            if (arrayProperties(obj, name)) {
                return null;
            }
        }
        PhpClass.PropertyResolution res = fast != null ? fast.res()
                : resolveFull(obj, name, effectiveCallerLower(frame, keys), ctx);
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
        bindCell(frame, keys, cell, PhpContext.get(this));
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell, PhpContext ctx) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError(msg("Attempt to assign property \"", name, "\" on ", PhpOps.typeName(target)), this);
        }
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null ? fast.arrayProps() : true) {
            if (arrayProperties(obj, name)) {
                arrayAccess().bindCell(obj, name, cell);
                return;
            }
        }
        checkEnumDynamicProperty(obj, name);
        PhpClass.PropertyResolution res = fast != null ? fast.res()
                : resolveFull(obj, name, effectiveCallerLower(frame, keys), ctx);
        checkIndirect(obj, name, res, frame, keys);
        if (!res.accessible()) {
            throw inaccessibleError(obj, name, effectiveCallerLower(frame, keys));
        }
        if (res.virtual() || res.hasHooks()) {
            throw indirectModification(obj, name);
        }
        String storageKey = res.storageKey();
        if (res.type() != null) {
            Object current = cell.getValue();
            Object checked = res.type().checkProperty(current, ctx, this, CallMode.strictAt(this),
                    selfClass(res, ctx), res.declaringClass(), name);
            if (checked != current) {
                cell.setValue(checked);
            }
            cell.setCellCheck(cellCheck(res, obj, name, ctx));
        }
        putNode.execute(obj, storageKey, cell);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        unset(frame, keys, PhpContext.get(this));
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError(msg("Attempt to modify property \"", name, "\" on ", PhpOps.typeName(target)), this);
        }
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null ? fast.arrayProps() : true) {
            if (arrayProperties(obj, name)) {
                arrayAccess().unset(obj, name);
                return;
            }
        }
        PhpClass.PropertyResolution res = fast != null ? fast.res()
                : resolveFull(obj, name, effectiveCallerLower(frame, keys), ctx);
        if (res.hasHooks()) {
            throw new PhpError(msg("Cannot unset hooked property ", obj.phpClass().name(), "::$", name), this);
        }
        if (res.accessible() && res.isReadonly()) {
            Object existing = res.storageKey() == null ? null : getNode.execute(obj, res.storageKey(), null);
            if (existing != PhpClass.UNINITIALIZED || !res.canInitReadonly()) {
                String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                CompilerDirectives.transferToInterpreter();
                throw new PhpError(msg("Cannot unset readonly property ", owner, "::$", name), this);
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

    private PhpClass selfClass(PhpClass.PropertyResolution res, PhpContext ctx) {
        String owner = res.declaringClass();
        return owner == null ? null : ctx.lookupClass(owner);
    }

    private Object checkType(DeclaredType type, PhpObject obj, String name, String owner, Object value,
                             PhpContext ctx) {
        if (type == null || type.matchesFast(value)) {
            // Scalars, null and untyped-enough values pass without resolving the owner class.
            return value;
        }
        return checkTypeSlow(type, obj, name, owner, value, ctx);
    }

    /** Coercion or rejection of a value that did not match its declared type. */
    @TruffleBoundary
    private Object checkTypeSlow(DeclaredType type, PhpObject obj, String name, String owner, Object value,
                                 PhpContext ctx) {
        return type.checkProperty(value, ctx, this, CallMode.strictAt(this),
                owner == null ? null : ownerClass(ctx, owner),
                owner != null ? owner : obj.phpClass().name(), name);
    }

    /** An owner name and its engine-wide class id, published together. */
    private record OwnerId(String owner, int id) {
    }

    /**
     * The last owner this site resolved. One immutable pair, as the AST is shared by concurrent
     * requests: two separate fields could pair one thread's name with another's id.
     */
    private OwnerId cachedOwner;

    /** The declaring class of a typed property through the context's id table, not a name lookup. */
    @TruffleBoundary
    private PhpClass ownerClass(PhpContext ctx, String owner) {
        OwnerId cached = cachedOwner;
        int id;
        if (cached != null && cached.owner() == owner) {
            id = cached.id();
        } else {
            id = ctx.language().classId(owner.startsWith("\\") ? owner.substring(1) : owner);
            cachedOwner = new OwnerId(owner, id);
        }
        PhpClass cls = ctx.classById(id);
        return cls != null ? cls : ctx.lookupClass(owner);
    }

    private PhpReference.CellCheck cellCheck(PhpClass.PropertyResolution res, PhpObject obj, String name,
                                            PhpContext ctx) {
        String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
        return res.type().propertyCellCheck(ctx, CallMode.strictAt(this), selfClass(res, ctx), owner, name);
    }

    @TruffleBoundary
    private Object readUninitialized(PhpObject obj, String name, String callerLower, PhpContext ctx) {
        PhpClass.MethodEntry get = obj.phpClass().getMethod();
        if (magicUsable(get, obj, name, PhpObject.GUARD_GET)) {
            return callMagicGuarded(get, obj, name, PhpObject.GUARD_GET);
        }
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(ctx, name, callerLower);
        String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Typed property " + owner + "::$" + name + " must not be accessed before initialization", this);
    }

    @TruffleBoundary
    private PhpClass.PropertyResolution resolveFull(PhpObject obj, String name, String callerLower,
                                                    PhpContext ctx) {
        return obj.phpClass().resolveProperty(ctx, name, callerLower);
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
    private static boolean magicUsable(PhpClass.MethodEntry entry, PhpObject obj, String name, int kind) {
        return entry != null && entry.callTarget() != null && !obj.inMagic(name, kind);
    }

    private Object callMagicGuarded(PhpClass.MethodEntry entry, PhpObject obj, String name, int kind) {
        obj.enterMagic(name, kind);
        try {
            return callMagic1(entry, obj, name);
        } finally {
            obj.exitMagic(name, kind);
        }
    }

    /** The magic call site: a direct call to the method's target when the class does not change. */
    @Child private dev.phpruntime.nodes.call.DispatchCallNode magicDispatch;

    private Object callMagic1(PhpClass.MethodEntry entry, PhpObject obj, String name) {
        dev.phpruntime.nodes.call.DispatchCallNode dispatch = magicDispatch;
        if (dispatch == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            dispatch = insert(dev.phpruntime.nodes.call.DispatchCallNode.create());
            magicDispatch = dispatch;
        }
        // callUnsitedMethod inserts the using class for a trait method itself.
        return dispatch.callUnsitedMethod(entry, new Object[]{obj, obj.phpClass(), name});
    }

    @TruffleBoundary
    private void writeFallback(PhpObject obj, String name, Object value, boolean inaccessible, String callerLower) {
        PhpClass.MethodEntry set = obj.phpClass().setMethod();
        if (magicUsable(set, obj, name, PhpObject.GUARD_SET)) {
            obj.enterMagic(name, PhpObject.GUARD_SET);
            try {
                if (set.fromTrait()) {
                    dev.phpruntime.runtime.PhpBacktrace.callUnsited(set.callTarget(), obj, obj.phpClass(), set.declaringClass(), name, value);
                } else {
                    dev.phpruntime.runtime.PhpBacktrace.callUnsited(set.callTarget(), obj, obj.phpClass(), name, value);
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

    private Resolution fastResolution(VirtualFrame frame, PhpObject obj, String name, Object[] keys,
                                      PhpContext ctx) {
        if (staticName != null) {
            PhpClass cls = obj.phpClass();
            long h = cls.hierarchyId();
            Resolution[] entries = cache;
            if (entries == MEGAMORPHIC) {
                return null;
            }
            Resolution front = lastResolution;
            if (front != null && h == front.hierarchyId()
                    && (!front.callerSensitive() || front.callerHierarchyId() == callerHierarchyId(frame, keys, ctx))) {
                return front;
            }
            Resolution hit = lookup(entries, h, frame, keys, ctx);
            if (hit != null) {
                return hit;
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cacheResolution(cls, h, effectiveCallerLower(frame, keys), callerHierarchyId(frame, keys, ctx), ctx);
            return lookup(cache, h, frame, keys, ctx);
        }
        return null;
    }

    @ExplodeLoop
    private Resolution lookup(Resolution[] entries, long h, VirtualFrame frame, Object[] keys, PhpContext ctx) {
        if (entries == null) {
            return null;
        }
        for (int i = 0; i < entries.length; i++) {
            Resolution c = entries[i];
            if (h == c.hierarchyId()
                    && (!c.callerSensitive() || c.callerHierarchyId() == callerHierarchyId(frame, keys, ctx))) {
                return c;
            }
        }
        return null;
    }

    private String resolveKeyIfAccessible(VirtualFrame frame, PhpObject obj, String name, Object[] keys,
                                          PhpContext ctx) {
        Resolution fast = fastResolution(frame, obj, name, keys, ctx);
        if (fast != null) {
            return fast.res().accessible() ? fast.res().storageKey() : null;
        }
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(ctx, name,
                effectiveCallerLower(frame, keys));
        return res.accessible() ? res.storageKey() : null;
    }

    @TruffleBoundary
    private PhpError inaccessibleError(PhpObject obj, String name, String callerLower) {
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callerLower);
        return inaccessibleError(res.visibility(), res.declaringClass(), name);
    }

    @TruffleBoundary
    private PhpClass.PropertyResolution cacheResolution(PhpClass cls, long h, String callerLower, long callerH,
                                                        PhpContext ctx) {
        PhpClass.PropertyResolution res = cls.resolveProperty(ctx, staticName, callerLower);
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            Resolution[] entries = cache;
            if (entries != MEGAMORPHIC) {
                Resolution entry = Resolution.of(h, callerH, cls, res, traitSelfSlot >= 0 || traitCallerInKeys);
                boolean present = false;
                if (entries != null) {
                    for (Resolution c : entries) {
                        present |= c.hierarchyId() == h && (!c.callerSensitive() || c.callerHierarchyId() == callerH);
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
                    if (next != MEGAMORPHIC) {
                        lastResolution = entry;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        return res;
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
        return obj.nativeState() instanceof SplArrayState state
                && (state.flags & SplArrayState.ARRAY_AS_PROPS) != 0
                && !obj.phpClass().hasDeclaredProperty(name);
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

    private long callerHierarchyId(VirtualFrame frame, Object[] keys, PhpContext ctx) {
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
        if (!callerStableLooked) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callerStable = dev.phpruntime.PhpLanguage.get(this).stableHierarchy(id);
            callerStableLooked = true;
        }
        // Running code's class is declared in this context, and while every context has the same
        // hierarchy for it the id is a constant: no context lookup or class table read.
        dev.phpruntime.PhpLanguage.StableHierarchy stable = callerStable;
        if (stable != null && stable.valid().isValid()) {
            return stable.id();
        }
        // An array read in this request's class table instead of a name lookup behind a boundary.
        PhpClass caller = ctx.classById(id);
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

    @Override
    public Object readValue(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        Object v = read(frame, keys, ctx);
        return v == null ? undefined(ctx) : v;
    }

}
