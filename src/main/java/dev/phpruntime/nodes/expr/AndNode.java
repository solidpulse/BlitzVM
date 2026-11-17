package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import dev.phpruntime.nodes.PhpNode;

/** Short-circuit {@code &&} / {@code and}. */
public final class AndNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;
    private final CountingConditionProfile profile = CountingConditionProfile.create();

    public AndNode(PhpNode left, PhpNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (profile.profile(CondNode.toBool(left.execute(frame)))) {
            return CondNode.toBool(right.execute(frame));
        }
        return false;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginPhpAnd();
        e.expr(left);
        e.expr(right);
        e.builder().endPhpAnd();
    }
}
