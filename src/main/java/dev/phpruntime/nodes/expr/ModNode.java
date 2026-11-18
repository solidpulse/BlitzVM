package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.nodes.PhpNode;

/** {@code %}. */
@NodeInfo(shortName = "%")
public final class ModNode extends BinaryNode {

    public ModNode(PhpNode left, PhpNode right) {
        super(left, right);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginMod();
        e.expr(getLeft());
        e.expr(getRight());
        b.endMod();
    }
}
