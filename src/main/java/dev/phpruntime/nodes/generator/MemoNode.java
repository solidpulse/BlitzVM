package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.CallArgHelper;

/** Stores completed operand results, including side effects preceding a yield in a later operand. */
public final class MemoNode extends PhpNode {
    private record Result(Object value) {}
    @Child private PhpNode expression;
    private final int slot;

    public MemoNode(PhpNode expression, int slot) {
        this.expression = expression;
        this.slot = slot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return CallArgHelper.unwrapReturn(executeForRef(frame));
    }

    @Override
    public Object executeForRef(VirtualFrame frame) {
        Object saved = frame.getObject(slot);
        if (saved instanceof Result result) {
            return result.value;
        }
        Object value = expression.executeForRef(frame);
        frame.setObject(slot, new Result(value));
        return value;
    }
}
