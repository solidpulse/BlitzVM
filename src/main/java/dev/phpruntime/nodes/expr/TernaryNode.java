package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** {@code c ? a : b}, and the short form {@code a ?: b} when {@code whenTrue} is null. */
public final class TernaryNode extends PhpNode {

    @Child private PhpNode condition;
    @Child private PhpNode whenTrue;
    @Child private PhpNode whenFalse;

    public TernaryNode(PhpNode condition, PhpNode whenTrue, PhpNode whenFalse) {
        this.condition = condition;
        this.whenTrue = whenTrue;
        this.whenFalse = whenFalse;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (whenTrue == null) {
            b.beginElvis();
            e.expr(condition);
            e.expr(whenFalse);
            b.endElvis();
            return;
        }
        b.beginConditional();
        b.beginToBool();
        e.expr(condition);
        b.endToBool();
        e.expr(whenTrue);
        e.expr(whenFalse);
        b.endConditional();
    }
}
