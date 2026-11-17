package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;

public final class BlockNode extends PhpNode {

    @Children private final PhpNode[] statements;

    public BlockNode(PhpNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (PhpNode s : statements) {
            try {
                s.executeVoid(frame);
            } catch (PhpError e) {
                // Runtime helpers throw without a node; blame the statement for file and line.
                throw e.at(s);
            }
        }
        return null;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        for (PhpNode s : statements) {
            e.stmt(s);
        }
        e.builder().endBlock();
    }
}
