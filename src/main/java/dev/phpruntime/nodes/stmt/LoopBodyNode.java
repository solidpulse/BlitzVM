package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.CondNode;

/**
 * One iteration of while / for / do-while. Truffle's LoopNode drives this, which
 * gives us on-stack replacement for long-running loops and a guest safepoint poll on
 * every back-edge: that poll is where the scheduler preempts a busy script.
 */
final class LoopBodyNode extends Node implements RepeatingNode {

    @Child private CondNode condition;   // null means "forever"
    @Child private PhpNode body;
    @Child private PhpNode update;       // for-loop step, may be null
    private final boolean conditionAfterBody;

    LoopBodyNode(PhpNode condition, PhpNode body, PhpNode update, boolean conditionAfterBody) {
        this.condition = condition == null ? null : new CondNode(condition);
        this.body = body;
        this.update = update;
        this.conditionAfterBody = conditionAfterBody;
    }

    @Override
    public boolean executeRepeating(VirtualFrame frame) {
        if (!conditionAfterBody && !test(frame)) {
            return false;
        }
        try {
            body.executeVoid(frame);
        } catch (ControlFlow.Break b) {
            if (b.levels > 1) {
                throw new ControlFlow.Break(b.levels - 1);
            }
            return false;
        } catch (ControlFlow.Continue c) {
            if (c.levels > 1) {
                throw new ControlFlow.Continue(c.levels - 1);
            }
        }
        if (update != null) {
            update.executeVoid(frame);
        }
        return !conditionAfterBody || test(frame);
    }

    private boolean test(VirtualFrame frame) {
        return condition == null || condition.executeCondition(frame);
    }

    /** Emits the loop into the enclosing block; break leaves it, continue runs the update next. */
    void emitLoop(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        com.oracle.truffle.api.bytecode.BytecodeLabel exit = b.createLabel();
        b.beginWhile();
        if (conditionAfterBody || condition == null) {
            b.emitLoadConstant(true);
        } else {
            e.expr(condition);
        }
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel next = b.createLabel();
        e.pushLoop(exit, next);
        if (body != null) {
            e.stmt(body);
        }
        e.popLoop();
        b.emitLabel(next);
        if (update != null) {
            e.stmt(update);
        }
        if (conditionAfterBody) {
            b.beginIfThen();
            b.beginNot();
            e.expr(condition);
            b.endNot();
            b.emitBranch(exit);
            b.endIfThen();
        }
        b.endBlock();
        b.endWhile();
        b.emitLabel(exit);
    }
}
