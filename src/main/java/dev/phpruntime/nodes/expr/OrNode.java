package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** Short-circuit {@code ||} / {@code or}. */
public final class OrNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;

    public OrNode(PhpNode left, PhpNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginPhpOr();
        e.expr(left);
        e.expr(right);
        e.builder().endPhpOr();
    }
}
