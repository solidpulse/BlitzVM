package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.ControlFlow;

public final class ResumableIfNode extends PhpNode implements dev.phpruntime.nodes.stmt.GotoEnterable {
    @Child private PhpNode condition;
    @Child private PhpNode thenBranch;
    @Child private PhpNode elseBranch;
    private final int stateSlot;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final int[] thenLabels;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final int[] labels;

    public ResumableIfNode(PhpNode condition, PhpNode thenBranch, PhpNode elseBranch, int stateSlot) {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
        this.stateSlot = stateSlot;
        thenLabels = thenBranch instanceof dev.phpruntime.nodes.stmt.GotoEnterable branch ? branch.exportedLabelIds() : new int[0];
        int[] elseLabels = elseBranch instanceof dev.phpruntime.nodes.stmt.GotoEnterable branch ? branch.exportedLabelIds() : new int[0];
        labels = new int[thenLabels.length + elseLabels.length];
        System.arraycopy(thenLabels, 0, labels, 0, thenLabels.length);
        System.arraycopy(elseLabels, 0, labels, thenLabels.length, elseLabels.length);
    }

    @Override
    public int[] exportedLabelIds() {
        return labels;
    }

    @Override
    @com.oracle.truffle.api.nodes.ExplodeLoop
    public void executeEnter(VirtualFrame frame, int labelId) {
        boolean then = false;
        for (int label : thenLabels) { if (label == labelId) { then = true; } }
        frame.setInt(stateSlot, then ? 1 : 2);
        PhpNode branch = then ? thenBranch : elseBranch;
        try {
            if (branch instanceof dev.phpruntime.nodes.stmt.GotoEnterable enterable) {
                enterable.executeEnter(frame, labelId);
            } else if (branch != null) {
                branch.executeVoid(frame);
            }
        } catch (ControlFlow.Yield suspended) {
            throw suspended;
        } catch (RuntimeException | Error error) {
            frame.setInt(stateSlot, 0);
            throw error;
        }
        frame.setInt(stateSlot, 0);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        int state = frame.getInt(stateSlot);
        if (state == 0) {
            state = dev.phpruntime.nodes.expr.CondNode.toBool(condition.execute(frame)) ? 1 : 2;
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
