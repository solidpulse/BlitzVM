package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** {@code < <= > >= <=>}. The kind is a final field so it constant-folds when compiled. */
public final class CompareNode extends BinaryNode {

    public enum Kind { LT, LE, GT, GE, SPACESHIP }

    protected final Kind kind;

    public CompareNode(Kind kind, PhpNode left, PhpNode right) {
        super(left, right);
        this.kind = kind;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginCompare(kind);
        e.expr(getLeft());
        e.expr(getRight());
        e.builder().endCompare();
    }
}
