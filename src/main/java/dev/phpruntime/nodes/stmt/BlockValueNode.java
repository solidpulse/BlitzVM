package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;

/** Evaluates expressions in order and yields the last one (comma lists in for-conditions). */
public final class BlockValueNode extends PhpNode {

    @Children private final PhpNode[] exprs;

    public BlockValueNode(PhpNode[] exprs) {
        this.exprs = exprs;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object last = null;
        for (PhpNode e : exprs) {
            last = e.execute(frame);
        }
        return last;
    }
}
