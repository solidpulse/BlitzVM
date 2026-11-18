package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** Short-circuit {@code &&} / {@code and}. */
public final class AndNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;

    public AndNode(PhpNode left, PhpNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginPhpAnd();
        e.expr(left);
        e.expr(right);
        e.builder().endPhpAnd();
    }
}
