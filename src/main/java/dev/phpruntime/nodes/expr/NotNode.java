package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

public final class NotNode extends PhpNode {

    @Child private PhpNode operand;

    public NotNode(PhpNode operand) {
        this.operand = operand;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginNot();
        e.expr(operand);
        e.builder().endNot();
    }
}
