package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.runtime.PhpArith;

@NodeInfo(shortName = "-")
public abstract class SubNode extends BinaryNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long doLong(long a, long b) {
        return Math.subtractExact(a, b);
    }

    @Specialization
    protected double doDouble(double a, double b) {
        return a - b;
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        return PhpArith.sub(a, b);
    }
}
