package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpReference;

/**
 * Something that can be assigned to: a variable or an array element path.
 * Keys are evaluated once up front so that {@code $a[$i++] += 1} has a single side effect.
 */
public abstract class LValueNode extends PhpNode {

    public static final Object[] NO_KEYS = new Object[0];

    public Object[] evalKeys(VirtualFrame frame) {
        return NO_KEYS;
    }

    /** Current value, or Java {@code null} if undefined. */
    public abstract Object read(VirtualFrame frame, Object[] keys);

    /** Returns whether this lvalue is set (non-null) for {@code isset}. */
    public boolean isSet(VirtualFrame frame, Object[] keys) {
        Object v = read(frame, keys);
        return v != null && v != dev.phpruntime.runtime.PhpNull.INSTANCE;
    }

    /** Reads this lvalue for {@code ??}; may check {@code __isset} before {@code __get}. */
    public Object readIfSet(VirtualFrame frame, Object[] keys) {
        return read(frame, keys);
    }

    public abstract void write(VirtualFrame frame, Object[] keys, Object value);

    /** Append without exposing mutable storage. Arrays/properties retain immutable stores. */
    public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded) {
        Object current = read(frame, keys);
        String result = dev.phpruntime.runtime.PhpOps.toStr(current == null
                ? dev.phpruntime.runtime.PhpNull.INSTANCE : current).concat(suffix);
        write(frame, keys, result);
        return result;
    }

    /**
     * Obtains (or creates) a {@link PhpReference} cell backing this lvalue so writes to the
     * returned cell mutate this variable, array element, or property in place.
     */
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        throw new dev.phpruntime.runtime.PhpError("Cannot take reference to " + describe(), this);
    }

    /**
     * Binds this lvalue directly to {@code cell} (for {@code $lhs = &$rhs} and {@code foreach (... as &$v)}).
     */
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        throw new dev.phpruntime.runtime.PhpError("Cannot assign by reference to " + describe(), this);
    }

    /** Typed writes; variables store these unboxed, everything else boxes. */
    public void writeLong(VirtualFrame frame, Object[] keys, long value) {
        write(frame, keys, value);
    }

    public void writeDouble(VirtualFrame frame, Object[] keys, double value) {
        write(frame, keys, value);
    }

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
