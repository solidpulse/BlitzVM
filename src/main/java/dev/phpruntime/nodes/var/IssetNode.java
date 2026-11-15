package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;

/** {@code isset($a, $b['k'])}: true only if every operand exists and is not null. Never warns. */
public final class IssetNode extends PhpNode {

    @Children private final LValueNode[] targets;

    public IssetNode(LValueNode[] targets) {
        this.targets = targets;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (LValueNode t : targets) {
            if (!t.isSet(frame, t.evalKeys(frame))) {
                return false;
            }
        }
        return true;
    }
}
