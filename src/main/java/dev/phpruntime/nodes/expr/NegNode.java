package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArith;

@NodeChild("operand")
public abstract class NegNode extends PhpNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long doLong(long a) {
        return Math.negateExact(a);
    }

    @Specialization
    protected double doDouble(double a) {
        return -a;
    }

    @Fallback
    protected Object doGeneric(Object a) {
        try {
            return PhpArith.negate(a);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }

    public abstract PhpNode getOperand();

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginNeg();
        e.expr(getOperand());
        e.builder().endNeg();
    }
}
