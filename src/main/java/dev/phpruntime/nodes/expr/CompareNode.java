package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import dev.phpruntime.runtime.PhpOps;

/** {@code < <= > >= <=>}. The kind is a final field so it constant-folds when compiled. */
public abstract class CompareNode extends BinaryNode {

    public enum Kind { LT, LE, GT, GE, SPACESHIP }

    protected final Kind kind;

    protected CompareNode(Kind kind) {
        this.kind = kind;
    }

    @Specialization
    protected Object doLong(long a, long b) {
        return result(Long.compare(a, b));
    }

    @Specialization
    protected Object doDouble(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return kind == Kind.SPACESHIP ? (Object) 1L : false;
        }
        return result(a < b ? -1 : (a > b ? 1 : 0));
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        return result(PhpOps.compare(a, b));
    }

    private Object result(int c) {
        return switch (kind) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            case SPACESHIP -> (long) Integer.signum(c);
        };
    }
}
