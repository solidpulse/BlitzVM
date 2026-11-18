package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** {@code a ?? b}. A variable or array element on the left is read without warnings, like isset. */
public final class CoalesceNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;

    public CoalesceNode(PhpNode left, PhpNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginCoalesce();
        e.ifSet(left);
        e.expr(right);
        e.builder().endCoalesce();
    }
}
