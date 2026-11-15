package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

/** Read-only {@code expr[key]} on an arbitrary expression, e.g. {@code f()[0]}. */
public final class IndexReadNode extends PhpNode {

    @Child private PhpNode target;
    @Child private PhpNode key;

    public IndexReadNode(PhpNode target, PhpNode key) {
        this.target = target;
        this.key = key;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object t = target.execute(frame);
        Object k = key.execute(frame);
        Object v;
        try {
            v = index(t, k);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
        if (v == null) {
            warn(k);
            return PhpNull.INSTANCE;
        }
        return v;
    }

    @TruffleBoundary
    private void warn(Object k) {
        PhpContext.get(this).warning("Undefined array key " + PhpOps.toStr(k), line());
    }

    /** Element of an array or character of a string; null when absent. */
    @TruffleBoundary
    public static Object index(Object container, Object k) {
        if (container instanceof PhpArray a) {
            return a.get(k);
        }
        if (container instanceof String s) {
            long i = PhpOps.toLong(k);
            if (i < 0) {
                i += s.length();
            }
            if (i < 0 || i >= s.length()) {
                return null;
            }
            return String.valueOf(s.charAt((int) i));
        }
        return null;
    }
}
