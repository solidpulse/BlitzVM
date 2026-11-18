package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArith;

/** Less common binary operators (**, bitwise, shifts) that only use the generic path. */
public final class ArithNode extends PhpNode {

    @Child private PhpNode left;
    @Child private PhpNode right;
    private final PhpArith.Op op;

    public ArithNode(PhpArith.Op op, PhpNode left, PhpNode right) {
        this.op = op;
        this.left = left;
        this.right = right;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginArith(op);
        e.expr(left);
        e.expr(right);
        e.builder().endArith();
    }
}
