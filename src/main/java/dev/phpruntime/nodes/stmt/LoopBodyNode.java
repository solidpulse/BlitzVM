package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.CondNode;

/** One iteration of while / for / do-while, emitted as a bytecode loop. */
final class LoopBodyNode extends Node {

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
