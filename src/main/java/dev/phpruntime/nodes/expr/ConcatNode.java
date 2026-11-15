package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import dev.phpruntime.runtime.PhpOps;

@NodeInfo(shortName = ".")
public abstract class ConcatNode extends BinaryNode {

    @Specialization
    @TruffleBoundary
    protected String doString(String a, String b) {
        return a.concat(b);
    }

    @Fallback
    @TruffleBoundary
    protected String doGeneric(Object a, Object b) {
        try {
            return PhpOps.toStr(a).concat(PhpOps.toStr(b));
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }
}
