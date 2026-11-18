package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** Strict {@code ===} / {@code !==}. */
public final class IdenticalNode extends BinaryNode {

    protected final boolean negate;

    public IdenticalNode(boolean negate, PhpNode left, PhpNode right) {
        super(left, right);
        this.negate = negate;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginIdentical(negate);
        e.expr(getLeft());
        e.expr(getRight());
        e.builder().endIdentical();
    }
}
