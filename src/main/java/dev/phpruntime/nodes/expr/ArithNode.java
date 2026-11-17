package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
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
    public Object execute(VirtualFrame frame) {
        Object a = left.execute(frame);
        Object b = right.execute(frame);
        try {
            return PhpArith.apply(op, a, b);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginArith(op);
        e.expr(left);
        e.expr(right);
        e.builder().endArith();
    }
}
