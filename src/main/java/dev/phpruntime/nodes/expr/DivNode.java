package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.runtime.PhpArith;

/** {@code /}: int when the division is exact, float otherwise. */
@NodeInfo(shortName = "/")
public abstract class DivNode extends BinaryNode {

    @Specialization(guards = "isExact(a, b)")
    protected long doExact(long a, long b) {
        return a / b;
    }

    @Specialization(guards = "isNonZero(b)")
    protected double doDouble(double a, double b) {
        return a / b;
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        return PhpArith.div(a, b);
    }

    /** Exact, non-overflowing division stays an int; b == -1 is excluded for Long.MIN_VALUE / -1. */
    protected static boolean isExact(long a, long b) {
        return b != 0 && b != -1 && a % b == 0;
    }

    protected static boolean isNonZero(double b) {
        return b != 0;
    }
}
