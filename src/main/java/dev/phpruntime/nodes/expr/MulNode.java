package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.nodes.PhpNode;

/** {@code *}. */
@NodeInfo(shortName = "*")
public final class MulNode extends BinaryNode {

    public MulNode(PhpNode left, PhpNode right) {
        super(left, right);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginMul();
        e.expr(getLeft());
        e.expr(getRight());
        b.endMul();
    }
}
