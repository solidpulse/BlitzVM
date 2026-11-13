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

    /** Two ints that do not divide exactly (or PHP_INT_MIN / -1, which overflows): a float. */
    @Specialization(guards = {"b != 0", "!isExact(a, b)"})
    protected double doInexact(long a, long b) {
        return (double) a / (double) b;
    }

    @Specialization(guards = "isNonZero(b)")
    protected double doLongDouble(long a, double b) {
        return a / b;
    }

    @Specialization(guards = "b != 0")
    protected double doDoubleLong(double a, long b) {
        return a / b;
    }

    @Specialization(guards = "isNonZero(b)")
    protected double doDouble(double a, double b) {
        return a / b;
    }

    /** Division by zero (an error in PHP 8), and non-numbers. */
    @Fallback
    protected Object doGeneric(Object a, Object b) {
        return PhpArith.div(a, b);
    }

    /** Exact and not PHP_INT_MIN / -1, the one int division that overflows. */
    protected static boolean isExact(long a, long b) {
        return b != 0 && a % b == 0 && !(a == Long.MIN_VALUE && b == -1);
    }

    protected static boolean isNonZero(double b) {
        return b != 0;
    }
}
