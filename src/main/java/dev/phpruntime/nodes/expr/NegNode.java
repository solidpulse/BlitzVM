package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** Unary {@code -}. */
public final class NegNode extends PhpNode {

    @Child private PhpNode operand;

    public NegNode(PhpNode operand) {
        this.operand = operand;
    }

    public PhpNode getOperand() {
        return operand;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginNeg();
        e.expr(operand);
        e.builder().endNeg();
    }
}
