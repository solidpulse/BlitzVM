package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpNull;

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
            Object v = t.read(frame, t.evalKeys(frame));
            if (v == null || v == PhpNull.INSTANCE) {
                return false;
            }
        }
        return true;
    }
}
