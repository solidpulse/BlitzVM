package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.Visibility;

/** {@code clone $obj} and PHP 8.5's {@code clone($obj, ['prop' => $value])}. */
public final class CloneNode extends PhpNode {

    @Child private PhpNode targetExpr;
    @Child private PhpNode withProperties;
    private final String callingClass;
    private final int traitSelfSlot;

    public CloneNode(PhpNode targetExpr, PhpNode withProperties, String callingClass, int traitSelfSlot) {
        this.targetExpr = targetExpr;
        this.withProperties = withProperties;
        this.callingClass = callingClass;
        this.traitSelfSlot = traitSelfSlot;
    }

    /** The semantics of {@code clone $value}; the bytecode operation passes this instruction's site. */
    public static Object cloneValue(Object val, Node site, DispatchCallNode dispatch) {
        if (!(val instanceof PhpObject obj)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("__clone method called on non-object", site);
        }
        if (obj.nativeState() instanceof dev.phpruntime.runtime.EnumCaseState) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Trying to clone an uncloneable object of class " + obj.phpClass().name(), site);
        }
        PhpObject copy = shallowCopy(PhpContext.get(site), obj);
        runCloneMethod(obj, copy, site, dispatch);
        return copy;
    }

    /** {@code clone($obj, $with)}: clone, let {@code __clone} run, then assign the new values. */
    public static Object cloneValueWith(Object val, Object with, Node site, DispatchCallNode dispatch,
                                        String callingClass, Object traitSelf) {
        if (!(val instanceof PhpObject obj)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR,
                    "clone(): Argument #1 ($object) must be of type object, " + PhpOps.typeName(val) + " given", site);
        }
        if (!(with instanceof PhpArray properties)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR,
                    "clone(): Argument #2 ($withProperties) must be of type array, "
                            + PhpOps.typeName(with) + " given", site);
        }
        if (obj.nativeState() instanceof dev.phpruntime.runtime.EnumCaseState) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Trying to clone an uncloneable object of class " + obj.phpClass().name(), site);
        }
        PhpObject copy = shallowCopy(PhpContext.get(site), obj);
        runCloneMethod(obj, copy, site, dispatch);
        applyProperties(copy, properties, callingClass,
                traitSelf instanceof PhpClass c ? c.template().lowerName() : null, site);
        return copy;
    }

    private static void runCloneMethod(PhpObject obj, PhpObject copy, Node site, DispatchCallNode dispatch) {
        PhpClass.MethodEntry cloneMethod = obj.phpClass().cloneMethod();
        if (cloneMethod != null && cloneMethod.callTarget() != null) {
            copy.setCloning(true);
            try {
                Object[] callArgs = cloneMethod.fromTrait()
                        ? new Object[]{copy, obj.phpClass(), cloneMethod.declaringClass()}
                        : new Object[]{copy, obj.phpClass()};
                dispatch.call(cloneMethod.callTarget(), callArgs);
            } catch (PhpError e) {
                throw e.at(site);
            } finally {
                copy.setCloning(false);
            }
        }
    }

    @TruffleBoundary
    private static void applyProperties(PhpObject obj, PhpArray properties, String callingClass,
                                        String traitCaller, Node site) {
        PhpContext ctx = PhpContext.get(site);
        String callerLower = traitCaller != null ? traitCaller
                : (callingClass == null ? null : dev.phpruntime.runtime.PhpStrings.lower(callingClass));
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        for (int pos = properties.firstPos(); pos >= 0; pos = properties.nextPos(pos)) {
            Object key = properties.keyAt(pos);
            Object raw = properties.rawValueAt(pos);
            String name = PhpOps.toStr(key);
            if (raw instanceof PhpReference) {
                throw new PhpError("Cannot assign by reference when cloning with updated properties", site);
            }
            Object value = raw == null ? PhpNull.INSTANCE : raw;
            PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(ctx, name, callerLower);
            if (!res.accessible()) {
                String level = res.visibility() == Visibility.PRIVATE ? "private" : "protected";
                String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                throw new PhpError("Cannot access " + level + " property " + owner + "::$" + name, site);
            }
            if (res.declaringClass() == null
                    && (obj.phpClass().template().isEnum() || obj.phpClass().isReadonlyClass())) {
                throw new PhpError("Cannot create dynamic property " + obj.phpClass().name() + "::$" + name, site);
            }
            String storageKey = res.storageKey();
            if (res.isReadonly()) {
                if (!res.canInitReadonly()) {
                    String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                    throw new PhpError("Cannot modify protected(set) readonly property " + owner + "::$"
                            + name + " from " + scopeName(ctx, callerLower), site);
                }
                put.execute(obj, storageKey, typed(res, value, obj, name, ctx, site));
                continue;
            }
            Object existing = get.execute(obj, storageKey, null);
            Object checked = typed(res, value, obj, name, ctx, site);
            if (existing instanceof PhpReference ref) {
                ref.setValue(checked);
            } else if (existing == null && res.declaringClass() == null
                    && obj.phpClass().setMethod() != null && obj.phpClass().setMethod().callTarget() != null) {
                PhpClass.MethodEntry set = obj.phpClass().setMethod();
                if (set.fromTrait()) {
                    set.callTarget().call(obj, obj.phpClass(), set.declaringClass(), name, checked);
                } else {
                    set.callTarget().call(obj, obj.phpClass(), name, checked);
                }
            } else {
                put.execute(obj, storageKey, checked);
            }
        }
    }

    private static Object typed(PhpClass.PropertyResolution res, Object value, PhpObject obj, String name,
                                PhpContext ctx, Node site) {
        if (res.type() == null) {
            return value;
        }
        PhpClass owner = res.declaringClass() == null ? null : ctx.lookupClass(res.declaringClass());
        return res.type().checkProperty(value, ctx, site, false, owner,
                res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name(), name);
    }

    private static String scopeName(PhpContext ctx, String callerLower) {
        if (callerLower == null) {
            return "global scope";
        }
        PhpClass caller = ctx.lookupClass(callerLower);
        return "scope " + (caller != null ? caller.name() : callerLower);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (withProperties == null) {
            e.builder().beginClone();
            e.expr(targetExpr);
            e.builder().endClone();
            return;
        }
        e.builder().beginCloneWith(callingClass == null ? "" : callingClass, traitSelfSlot);
        e.expr(targetExpr);
        e.expr(withProperties);
        e.builder().endCloneWith();
    }

    @TruffleBoundary
    private static PhpObject shallowCopy(PhpContext ctx, PhpObject src) {
        PhpObject copy = new PhpObject(
                src.phpClass(),
                ctx.nextObjectId(),
                src.closure() != null ? src.closure().copy() : null);
        if (src.nativeState() != null) {
            copy.setNativeState(src.nativeState().copy());
        }
        Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(src);
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        for (Object k : keys) {
            Object raw = get.execute(src, k, PhpNull.INSTANCE);
            if (raw == PhpClass.UNINITIALIZED) {
                put.execute(copy, k, PhpClass.UNINITIALIZED);
                continue;
            }
            if (raw instanceof dev.phpruntime.runtime.PhpReference ref) {
                // PHP clone preserves explicit aliases, not a copy of their current values.
                put.execute(copy, k, ref);
                continue;
            }
            put.execute(copy, k, PhpOps.copyOnAssign(raw == null ? PhpNull.INSTANCE : raw));
        }
        return copy;
    }
}
