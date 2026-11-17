package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.runtime.PhpArith;

/**
 * {@code +}. Starts on the int fast path; on overflow the node rewrites itself to the
 * float specialisation (PHP turns int overflow into float), and anything else goes to
 * the generic path (numeric strings, null, array union).
 */
@NodeInfo(shortName = "+")
public abstract class AddNode extends BinaryNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long doLong(long a, long b) {
        return Math.addExact(a, b);
    }

    /**
     * Ints after this node has seen one overflow (which removed doLong): the result is
     * still an int when it fits. Without this, int operands would fall through to
     * doDouble by implicit cast and 1 + 2 would come out as float(3).
     */
    @Specialization
    protected Object doLongChecked(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return (double) a + (double) b;
        }
    }

    @Specialization
    protected double doLongDouble(long a, double b) {
        return a + b;
    }

    @Specialization
    protected double doDoubleLong(double a, long b) {
        return a + b;
    }

    @Specialization
    protected double doDouble(double a, double b) {
        return a + b;
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        try {
            return PhpArith.add(a, b);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginAdd();
        e.expr(getLeft());
        e.expr(getRight());
        b.endAdd();
    }
}
