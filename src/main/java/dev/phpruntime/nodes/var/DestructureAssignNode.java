package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;

/** {@code list(...) = expr} or {@code [...] = expr}. */
public final class DestructureAssignNode extends PhpNode {

    @Child private DestructureLValueNode target;
    @Child private PhpNode value;
    @Child private LValueNode valueLValue;

    public DestructureAssignNode(DestructureLValueNode target, PhpNode value, LValueNode valueLValue) {
        this.target = target;
        this.value = value;
        this.valueLValue = target.hasAnyRef() ? valueLValue : null;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return target.assignFrom(frame, value, valueLValue);
    }
}
