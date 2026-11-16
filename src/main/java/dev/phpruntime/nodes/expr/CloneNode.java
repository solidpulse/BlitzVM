package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
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
        Object val = targetExpr.execute(frame);
        if (!(val instanceof PhpObject obj)) {
            throw new PhpError("__clone method called on non-object", this);
        }
        PhpObject copy = shallowCopy(PhpContext.get(this), obj);
        PhpClass.MethodEntry cloneMethod = obj.phpClass().cloneMethod();
        if (cloneMethod != null && cloneMethod.callTarget() != null) {
            try {
                dispatch.call(cloneMethod.callTarget(), new Object[]{copy, obj.phpClass()});
            } catch (PhpError e) {
                throw e.at(this);
            }
        }
        return copy;
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
            put.execute(copy, k, PhpOps.copyOnAssign(get.execute(src, k, PhpNull.INSTANCE)));
        }
        return copy;
    }
}
