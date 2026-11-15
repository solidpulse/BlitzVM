package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.Visibility;

/** {@code $obj->prop} or {@code $obj->$name} lvalue. */
public final class PropertyLValueNode extends LValueNode {

    @Child private PhpNode targetExpr;
    private final String staticName;
    @Child private PhpNode dynamicNameExpr;
    private final String callingClassLower;

    @Child private DynamicObject.GetNode getNode = DynamicObject.GetNode.create();
    @Child private DynamicObject.PutNode putNode = DynamicObject.PutNode.create();
    @Child private DynamicObject.RemoveKeyNode removeNode = DynamicObject.RemoveKeyNode.create();

    /**
     * How {@link #staticName} resolves for one class hierarchy. Published as a single immutable
     * object so a request reading it while another installs it never sees half an entry.
     */
    private record Resolution(long hierarchyId, long callerHierarchyId, String storageKey,
                              boolean isProtected, boolean accessible) {
    }

    private static final Resolution MEGAMORPHIC = new Resolution(-2, -2, null, false, false);

    @CompilationFinal private Resolution cache;

    public PropertyLValueNode(PhpNode targetExpr, String staticName, PhpNode dynamicNameExpr, String callingClassLower) {
        this.targetExpr = targetExpr;
        this.staticName = staticName;
        this.dynamicNameExpr = dynamicNameExpr;
        this.callingClassLower = callingClassLower;
    }

    @Override
    public Object[] evalKeys(VirtualFrame frame) {
        Object target = targetExpr.execute(frame);
        String name = staticName != null ? staticName : PhpOps.toStr(dynamicNameExpr.execute(frame));
        return new Object[]{target, name};
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeOnTarget(frame, targetExpr.execute(frame));
    }

    /** Executes this property read given an already-evaluated receiver (used by nullsafe {@code ?->}). */
    public Object executeOnTarget(VirtualFrame frame, Object target) {
        String name = staticName != null ? staticName : PhpOps.toStr(dynamicNameExpr.execute(frame));
        if (!(target instanceof PhpObject obj)) {
            PhpContext.get(this).warning(
                    "Attempt to read property \"" + name + "\" on " + PhpOps.typeName(target), line());
            return PhpNull.INSTANCE;
        }
        String storageKey = resolveKeyIfAccessible(obj, name);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
            if (v != null) {
                return v;
            }
        }
        return readFallback(obj, name, storageKey == null, true);
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return null;
        }
        String name = (String) keys[1];
        String storageKey = resolveKeyIfAccessible(obj, name);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
            if (v != null) {
                return v;
            }
        }
        return readFallback(obj, name, storageKey == null, false);
    }

    @Override
    public boolean isSet(VirtualFrame frame, Object[] keys) {
        if (!(keys[0] instanceof PhpObject obj)) {
            return false;
        }
        String name = (String) keys[1];
        String storageKey = resolveKeyIfAccessible(obj, name);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
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
        String storageKey = resolveKeyIfAccessible(obj, name);
        if (storageKey != null) {
            Object v = getNode.execute(obj, storageKey, null);
            if (v != null) {
                return v;
            }
        }
        if (checkMagicIsset(obj, name)) {
            PhpClass.MethodEntry get = obj.phpClass().getMethod();
            return get != null && get.callTarget() != null ? callMagic1(get, obj, name) : PhpNull.INSTANCE;
        }
        return null;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            throw new PhpError("Attempt to assign property \"" + name + "\" on " + PhpOps.typeName(target), this);
        }
        String storageKey = resolveKeyIfAccessible(obj, name);
        if (storageKey != null && (getNode.execute(obj, storageKey, null) != null
                || obj.phpClass().setMethod() == null)) {
            putNode.execute(obj, storageKey, value);
            return;
        }
        writeFallback(obj, name, value, storageKey == null);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        Object target = keys[0];
        String name = (String) keys[1];
        if (!(target instanceof PhpObject obj)) {
            throw new PhpError("Attempt to modify property \"" + name + "\" on " + PhpOps.typeName(target), this);
        }
        String storageKey = resolveKeyIfAccessible(obj, name);
        if (storageKey != null && getNode.execute(obj, storageKey, null) != null) {
            removeNode.execute(obj, storageKey);
            return;
        }
        unsetFallback(obj, name, storageKey == null);
    }

    @TruffleBoundary
    private Object readFallback(PhpObject obj, String name, boolean inaccessible, boolean warnMissing) {
        PhpClass.MethodEntry get = obj.phpClass().getMethod();
        if (get != null && get.callTarget() != null) {
            return get.callTarget().call(obj, obj.phpClass(), name);
        }
        if (inaccessible) {
            throw inaccessibleError(obj, name);
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
        if (isset != null && isset.callTarget() != null) {
            return PhpOps.toBool(isset.callTarget().call(obj, obj.phpClass(), name));
        }
        return false;
    }

    @TruffleBoundary
    private Object callMagic1(PhpClass.MethodEntry entry, PhpObject obj, String name) {
        return entry.callTarget().call(obj, obj.phpClass(), name);
    }

    @TruffleBoundary
    private void writeFallback(PhpObject obj, String name, Object value, boolean inaccessible) {
        PhpClass.MethodEntry set = obj.phpClass().setMethod();
        if (set != null && set.callTarget() != null) {
            set.callTarget().call(obj, obj.phpClass(), name, value);
            return;
        }
        if (inaccessible) {
            throw inaccessibleError(obj, name);
        }
    }

    @TruffleBoundary
    private void unsetFallback(PhpObject obj, String name, boolean inaccessible) {
        PhpClass.MethodEntry unset = obj.phpClass().unsetMethod();
        if (unset != null && unset.callTarget() != null) {
            unset.callTarget().call(obj, obj.phpClass(), name);
            return;
        }
        if (inaccessible) {
            throw inaccessibleError(obj, name);
        }
    }

    private String resolveKeyIfAccessible(PhpObject obj, String name) {
        if (staticName != null) {
            PhpClass cls = obj.phpClass();
            long h = cls.hierarchyId();
            Resolution c = cache;
            if (c != null && h == c.hierarchyId() && (!c.isProtected() || c.callerHierarchyId() == callerHierarchyId())) {
                return c.accessible() ? c.storageKey() : null;
            }
            if (c != MEGAMORPHIC) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                PhpClass.PropertyResolution res = cacheResolution(cls, h);
                return res.accessible() ? res.storageKey() : null;
            }
        }
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callingClassLower);
        return res.accessible() ? res.storageKey() : null;
    }

    @TruffleBoundary
    private PhpError inaccessibleError(PhpObject obj, String name) {
        PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(PhpContext.get(this), name, callingClassLower);
        return inaccessibleError(res.visibility(), res.declaringClass(), name);
    }

    @TruffleBoundary
    private PhpClass.PropertyResolution cacheResolution(PhpClass cls, long h) {
        PhpContext ctx = PhpContext.get(this);
        PhpClass.PropertyResolution res = cls.resolveProperty(ctx, staticName, callingClassLower);
        long callerH = callerHierarchyIdBoundary(ctx);
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (cache == null) {
                cache = new Resolution(h, callerH, res.storageKey(), res.visibility() == Visibility.PROTECTED, res.accessible());
            } else if (cache.hierarchyId() != h) {
                cache = MEGAMORPHIC;
            }
        } finally {
            lock.unlock();
        }
        return res;
    }

    private long callerHierarchyId() {
        if (callingClassLower == null) {
            return -1;
        }
        return callerHierarchyIdBoundary(PhpContext.get(this));
    }

    @TruffleBoundary
    private long callerHierarchyIdBoundary(PhpContext ctx) {
        if (callingClassLower == null) {
            return -1;
        }
        PhpClass caller = ctx.lookupClass(callingClassLower);
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
}
