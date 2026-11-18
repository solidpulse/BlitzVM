package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** Base for binary operators; children are evaluated left to right. */
public abstract class BinaryNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;

    protected BinaryNode(PhpNode left, PhpNode right) {
        this.left = left;
        this.right = right;
    }

    public final PhpNode getLeft() {
        return left;
    }

    public final PhpNode getRight() {
        return right;
    }
}
