package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.MethodCallNode;
import dev.phpruntime.nodes.var.PropertyLValueNode;
import dev.phpruntime.runtime.PhpNull;

/** {@code $obj?->prop} or {@code $obj?->method(args...)}: returns {@code null} when receiver is {@code null}. */
public final class NullsafeMemberNode extends PhpNode {

    @Child private PhpNode targetExpr;
    @Child private PropertyLValueNode propertyNode;
    @Child private MethodCallNode methodNode;

    public NullsafeMemberNode(PhpNode targetExpr, PropertyLValueNode propertyNode) {
        this.targetExpr = targetExpr;
        this.propertyNode = propertyNode;
        this.methodNode = null;
    }

    public NullsafeMemberNode(PhpNode targetExpr, MethodCallNode methodNode) {
        this.targetExpr = targetExpr;
        this.propertyNode = null;
        this.methodNode = methodNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = targetExpr.execute(frame);
        if (target == PhpNull.INSTANCE) {
            return PhpNull.INSTANCE;
        }
        if (propertyNode != null) {
            return propertyNode.executeOnTarget(frame, target);
        }
        return methodNode.executeOnTarget(frame, target);
    }

    @Override
    public Object executeForRef(VirtualFrame frame) {
        Object target = targetExpr.execute(frame);
        if (target == PhpNull.INSTANCE) {
            return PhpNull.INSTANCE;
        }
        if (propertyNode != null) {
            return propertyNode.executeOnTarget(frame, target);
        }
        return methodNode.executeOnTargetRaw(frame, target);
    }
}
