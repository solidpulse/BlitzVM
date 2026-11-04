package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpNull;

/** {@code a ?? b}. A variable or array element on the left is read without warnings, like isset. */
public final class CoalesceNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;

    public CoalesceNode(PhpNode left, PhpNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v;
        if (left instanceof LValueNode lv) {
            v = lv.read(frame, lv.evalKeys(frame));
        } else {
            v = left.execute(frame);
        }
        if (v == null || v == PhpNull.INSTANCE) {
            return right.execute(frame);
        }
        return v;
    }
}
