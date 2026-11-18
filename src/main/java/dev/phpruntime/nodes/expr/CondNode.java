package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** Evaluates a condition to a Java boolean; the boolean case avoids the generic conversion. */
public final class CondNode extends PhpNode {

    @Child private PhpNode operand;

    public CondNode(PhpNode operand) {
        this.operand = operand;
    }

    public static boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        return PhpOps.toBool(v);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginToBool();
        e.expr(operand);
        e.builder().endToBool();
    }
}
