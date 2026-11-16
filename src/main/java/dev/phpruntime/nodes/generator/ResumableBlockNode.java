package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.ControlFlow;

/** Saves the index of the suspended statement, not a request object on the shared AST. */
public final class ResumableBlockNode extends PhpNode {
    @Children private final PhpNode[] statements;
    private final int stateSlot;

    public ResumableBlockNode(PhpNode[] statements, int stateSlot) {
        this.statements = statements;
        this.stateSlot = stateSlot;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        int start = frame.getInt(stateSlot);
        try {
            for (int i = 0; i < statements.length; i++) {
                if (i >= start) {
                    frame.setInt(stateSlot, i);
                    statements[i].executeVoid(frame);
                }
            }
        } catch (ControlFlow.Yield suspend) {
            throw suspend;
        } catch (RuntimeException | Error error) {
            frame.setInt(stateSlot, 0);
            throw error;
        }
        frame.setInt(stateSlot, 0);
        return null;
    }
}
