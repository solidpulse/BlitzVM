package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpObject;

/** {@code $obj instanceof ClassName}, {@code $obj instanceof self/parent/static/$var}. */
public final class InstanceofNode extends PhpNode {

    @Child private PhpNode left;
    @Child private ClassRefNode right;

    public InstanceofNode(PhpNode left, ClassRefNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object lv = left.execute(frame);
        PhpClass target = right.resolveForInstanceof(frame);
        return lv instanceof PhpObject obj && target != null && obj.phpClass().isInstanceOf(target);
    }
}
