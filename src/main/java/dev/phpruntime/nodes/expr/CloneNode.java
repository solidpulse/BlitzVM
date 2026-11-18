package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** {@code clone $obj}: shallow copy of properties, then {@code __clone()}. */
public final class CloneNode extends PhpNode {

    @Child private PhpNode targetExpr;
    @Child private DispatchCallNode dispatch;

    public CloneNode(PhpNode targetExpr) {
        this.targetExpr = targetExpr;
        this.dispatch = new DispatchCallNode();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return cloneValue(targetExpr.execute(frame), this, dispatch);
    }

    /** The semantics of {@code clone $value}; the bytecode operation passes this instruction's site. */
    public static Object cloneValue(Object val, Node site, DispatchCallNode dispatch) {
        if (!(val instanceof PhpObject obj)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("__clone method called on non-object", site);
        }
        PhpObject copy = shallowCopy(PhpContext.get(site), obj);
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
        return copy;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginClone();
        e.expr(targetExpr);
        e.builder().endClone();
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
