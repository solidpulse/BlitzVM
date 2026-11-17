package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import dev.phpruntime.runtime.PhpOps;

/**
 * Strict {@code ===} / {@code !==}. Deliberately no double specialisation: the
 * implicit long-to-double cast would make {@code 1 === 1.0} true.
 */
public abstract class IdenticalNode extends BinaryNode {

    protected final boolean negate;

    protected IdenticalNode(boolean negate) {
        this.negate = negate;
    }

    @Specialization
    protected boolean doLong(long a, long b) {
        return (a == b) != negate;
    }

    @Specialization
    protected boolean doBoolean(boolean a, boolean b) {
        return (a == b) != negate;
    }

    @Fallback
    protected boolean doGeneric(Object a, Object b) {
        return PhpOps.strictEquals(a, b) != negate;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginIdentical(negate);
        e.expr(getLeft());
        e.expr(getRight());
        e.builder().endIdentical();
    }
}
