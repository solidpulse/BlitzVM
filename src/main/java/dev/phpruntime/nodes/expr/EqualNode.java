package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import dev.phpruntime.runtime.PhpOps;

/** Loose {@code ==} / {@code !=} with PHP 8 numeric-string semantics. */
public abstract class EqualNode extends BinaryNode {

    protected final boolean negate;

    protected EqualNode(boolean negate) {
        this.negate = negate;
    }

    @Specialization
    protected boolean doLong(long a, long b) {
        return (a == b) != negate;
    }

    @Specialization
    protected boolean doDouble(double a, double b) {
        return (a == b) != negate;
    }

    @Specialization
    protected boolean doLongDouble(long a, double b) {
        return (a == b) != negate;
    }

    @Specialization
    protected boolean doDoubleLong(double a, long b) {
        return (a == b) != negate;
    }

    @Specialization
    protected boolean doBoolean(boolean a, boolean b) {
        return (a == b) != negate;
    }

    @Fallback
    protected boolean doGeneric(Object a, Object b) {
        return PhpOps.looseEquals(a, b) != negate;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginEqual(negate);
        e.expr(getLeft());
        e.expr(getRight());
        e.builder().endEqual();
    }
}
