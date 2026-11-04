package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpNull;

/** break N, continue N, return expr. */
public final class JumpNode extends PhpNode {

    public enum Kind { BREAK, CONTINUE, RETURN }

    private final Kind kind;
    private final int levels;
    @Child private PhpNode value;

    public JumpNode(Kind kind, int levels, PhpNode value) {
        this.kind = kind;
        this.levels = levels;
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        switch (kind) {
            case BREAK -> throw new ControlFlow.Break(levels);
            case CONTINUE -> throw new ControlFlow.Continue(levels);
            default -> throw new ControlFlow.Return(value == null ? PhpNull.INSTANCE : value.execute(frame));
        }
    }
}
