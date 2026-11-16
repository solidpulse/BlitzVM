package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;

/** {@code goto label;}: transfers control via {@link ControlFlow.Goto}. */
public final class GotoNode extends PhpNode {

    private final int labelId;

    public GotoNode(int labelId) {
        this.labelId = labelId;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        throw new ControlFlow.Goto(labelId);
    }
}
