package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import dev.phpruntime.nodes.PhpNode;

/** Short-circuit {@code ||} / {@code or}. */
public final class OrNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;
    private final CountingConditionProfile profile = CountingConditionProfile.create();

    public OrNode(PhpNode left, PhpNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (profile.profile(CondNode.toBool(left.execute(frame)))) {
            return true;
        }
        return CondNode.toBool(right.execute(frame));
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginPhpOr();
        e.expr(left);
        e.expr(right);
        e.builder().endPhpOr();
    }
}
