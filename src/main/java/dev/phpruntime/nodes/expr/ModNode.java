package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.runtime.PhpArith;

@NodeInfo(shortName = "%")
public abstract class ModNode extends BinaryNode {

    /**
     * x % -1 is always 0 in PHP, and testing for it here, not in a second guard, also
     * keeps PHP_INT_MIN % -1 from overflowing. The two-guard form made GraalVM 25.5's
     * compiler fail ("input ValuePhi is not alive") on a loop like gcd's once the
     * operands were unboxed longs, which left the function interpreted.
     */
    @Specialization(guards = "b != 0")
    protected long doLong(long a, long b) {
        return b == -1 ? 0 : a % b;
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        try {
            return PhpArith.mod(a, b);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginMod();
        e.expr(getLeft());
        e.expr(getRight());
        b.endMod();
    }
}
