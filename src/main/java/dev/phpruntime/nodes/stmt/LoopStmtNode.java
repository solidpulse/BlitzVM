package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import dev.phpruntime.nodes.PhpNode;

/** while, for and do-while. The for-loop init runs before the Truffle loop. */
public final class LoopStmtNode extends PhpNode {

    @Child private PhpNode init;
    @Child private LoopNode loop;

    private LoopStmtNode(PhpNode init, LoopBodyNode body) {
        this.init = init;
        this.loop = Truffle.getRuntime().createLoopNode(body);
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
    public Object execute(VirtualFrame frame) {
        if (init != null) {
            init.executeVoid(frame);
        }
        loop.execute(frame);
        return null;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        if (init != null) {
            e.stmt(init);
        }
        ((LoopBodyNode) loop.getRepeatingNode()).emitLoop(e);
        e.builder().endBlock();
    }
}
