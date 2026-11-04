package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;

/**
 * Something that can be assigned to: a variable or an array element path.
 * Keys are evaluated once up front so that {@code $a[$i++] += 1} has a single side effect.
 */
public abstract class LValueNode extends PhpNode {

    protected static final Object[] NO_KEYS = new Object[0];

    public Object[] evalKeys(VirtualFrame frame) {
        return NO_KEYS;
    }

    /** Current value, or Java {@code null} if undefined. */
    public abstract Object read(VirtualFrame frame, Object[] keys);

    public abstract void write(VirtualFrame frame, Object[] keys, Object value);

    public abstract void unset(VirtualFrame frame, Object[] keys);

    /** Name used in diagnostics, e.g. "$x". */
    public abstract String describe();

    /** Reading an lvalue as an expression yields its value, warning when undefined. */
    @Override
    public Object execute(VirtualFrame frame) {
        Object v = read(frame, evalKeys(frame));
        if (v == null) {
            return undefined();
        }
        return v;
    }

    protected Object undefined() {
        dev.phpruntime.PhpContext.get(this).warning("Undefined variable " + describe(), line());
        return dev.phpruntime.runtime.PhpNull.INSTANCE;
    }
}
