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

    @Specialization
    protected double doDouble(double a, double b) {
        return a + b;
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        return PhpArith.add(a, b);
    }
}
