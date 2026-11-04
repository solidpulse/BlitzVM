package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.CondNode;

/** if/else; elseif chains are nested IfNodes. */
public final class IfNode extends PhpNode {

    @Child private CondNode condition;
    @Child private PhpNode thenBranch;
    @Child private PhpNode elseBranch;
    private final CountingConditionProfile profile = CountingConditionProfile.create();

    public IfNode(PhpNode condition, PhpNode thenBranch, PhpNode elseBranch) {
        this.condition = new CondNode(condition);
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (profile.profile(condition.executeCondition(frame))) {
            thenBranch.executeVoid(frame);
        } else if (elseBranch != null) {
            elseBranch.executeVoid(frame);
        }
        return null;
    }
}
