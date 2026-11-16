package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.runtime.PhpOps;

public final class ResumableIfNode extends PhpNode {
    @Child private PhpNode condition;
    @Child private PhpNode thenBranch;
    @Child private PhpNode elseBranch;
    private final int stateSlot;

    public ResumableIfNode(PhpNode condition, PhpNode thenBranch, PhpNode elseBranch, int stateSlot) {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
        this.stateSlot = stateSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        int state = frame.getInt(stateSlot);
        if (state == 0) {
            state = PhpOps.toBool(condition.execute(frame)) ? 1 : 2;
            frame.setInt(stateSlot, state);
        }
        try {
            if (state == 1) {
                thenBranch.executeVoid(frame);
            } else if (elseBranch != null) {
                elseBranch.executeVoid(frame);
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
