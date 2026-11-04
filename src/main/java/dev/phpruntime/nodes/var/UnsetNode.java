package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;

/** {@code unset($a, $b['k'])}. */
public final class UnsetNode extends PhpNode {

    @Children private final LValueNode[] targets;

    public UnsetNode(LValueNode[] targets) {
        this.targets = targets;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (LValueNode t : targets) {
            t.unset(frame, t.evalKeys(frame));
        }
        return null;
    }
}
