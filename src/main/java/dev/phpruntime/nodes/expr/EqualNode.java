package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** Loose {@code ==} / {@code !=} with PHP 8 numeric-string semantics. */
public final class EqualNode extends BinaryNode {

    protected final boolean negate;

    public EqualNode(boolean negate, PhpNode left, PhpNode right) {
        super(left, right);
        this.negate = negate;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginEqual(negate);
        e.expr(getLeft());
        e.expr(getRight());
        e.builder().endEqual();
    }
}
