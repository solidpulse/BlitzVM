package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;

/**
 * {@code global $a;} at file scope: the variable already is the global, but php creates it
 * (as null) if it does not exist yet, so later reads do not warn.
 */
public final class EnsureVariablesNode extends PhpNode {

    @Children private final LValueNode[] targets;

    public EnsureVariablesNode(LValueNode[] targets) {
        this.targets = targets;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (LValueNode t : targets) {
            t.obtainCell(frame, t.evalKeys(frame));
        }
        return null;
    }
}
