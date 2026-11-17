package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;

public final class NotNode extends PhpNode {

    @Child private PhpNode operand;

    public NotNode(PhpNode operand) {
        this.operand = operand;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return !CondNode.toBool(operand.execute(frame));
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginNot();
        e.expr(operand);
        e.builder().endNot();
    }
}
