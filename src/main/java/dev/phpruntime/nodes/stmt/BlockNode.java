package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;

public final class BlockNode extends PhpNode {

    @Children private final PhpNode[] statements;

    public BlockNode(PhpNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (PhpNode s : statements) {
            s.executeVoid(frame);
        }
        return null;
    }
}
