package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.runtime.PhpArith;

@NodeInfo(shortName = "%")
public abstract class ModNode extends BinaryNode {

    @Specialization(guards = {"b != 0", "isSafeDivisor(b)"})
    protected long doLong(long a, long b) {
        return a % b;
    }

    @Fallback
    protected Object doGeneric(Object a, Object b) {
        return PhpArith.mod(a, b);
    }

    /** b == -1 is excluded so Long.MIN_VALUE / -1 cannot overflow on the fast path. */
    protected static boolean isSafeDivisor(long b) {
        return b != -1;
    }
}
