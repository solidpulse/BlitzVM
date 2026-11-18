package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/** while, for and do-while. The for-loop init runs before the loop. */
public final class LoopStmtNode extends PhpNode {

    @Child private PhpNode init;
    @Child private LoopBodyNode body;

    private LoopStmtNode(PhpNode init, LoopBodyNode body) {
        this.init = init;
        this.body = body;
    }

    public static LoopStmtNode whileLoop(PhpNode cond, PhpNode body) {
        return new LoopStmtNode(null, new LoopBodyNode(cond, body, null, false));
    }

    public static LoopStmtNode doWhile(PhpNode body, PhpNode cond) {
        return new LoopStmtNode(null, new LoopBodyNode(cond, body, null, true));
    }

    public static LoopStmtNode forLoop(PhpNode init, PhpNode cond, PhpNode update, PhpNode body) {
        return new LoopStmtNode(init, new LoopBodyNode(cond, body, update, false));
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        if (init != null) {
            e.stmt(init);
        }
        body.emitLoop(e);
        e.builder().endBlock();
    }
}
