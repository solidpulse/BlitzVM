package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;

/** An expression's completed operands are retained only until the enclosing evaluation finishes. */
public final class ResumableExpressionNode extends PhpNode {
    @Child private PhpNode expression;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final int[] memoSlots;

    public ResumableExpressionNode(PhpNode expression, int[] memoSlots) {
        this.expression = expression;
        this.memoSlots = memoSlots;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        try {
            Object value = expression.execute(frame);
            clear(frame);
            return value;
        } catch (ControlFlow.Yield suspended) {
            throw suspended;
        } catch (RuntimeException | Error error) {
            clear(frame);
            throw error;
        }
    }

    @ExplodeLoop
    private void clear(VirtualFrame frame) {
        for (int slot : memoSlots) {
            frame.setObject(slot, null);
        }
    }
}
