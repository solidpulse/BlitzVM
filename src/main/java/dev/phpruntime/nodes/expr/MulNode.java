package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.runtime.PhpArith;

@NodeInfo(shortName = "*")
public abstract class MulNode extends BinaryNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long doLong(long a, long b) {
        return Math.multiplyExact(a, b);
    }

    /**
     * Ints after this node has seen one overflow (which removed doLong): the result is
     * still an int when it fits. Without this, int operands would fall through to
     * doDouble by implicit cast and 1 + 2 would come out as float(3).
     */
    @Specialization
    protected Object doLongChecked(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return (double) a * (double) b;
        }
    }

    @Specialization
    protected double doLongDouble(long a, double b) {
        return a * b;
    }

    @Specialization
    protected double doDoubleLong(double a, long b) {
        return a * b;
    }

    @Specialization
    protected double doDouble(double a, double b) {
        return a * b;
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        try {
            return PhpArith.mul(a, b);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }
}
