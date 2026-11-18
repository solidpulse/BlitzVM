package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.nodes.PhpNode;

@NodeInfo(shortName = ".")
public final class ConcatNode extends BinaryNode {

    public ConcatNode(PhpNode left, PhpNode right) {
        super(left, right);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginConcat();
        e.expr(getLeft());
        e.expr(getRight());
        b.endConcat();
    }
}
