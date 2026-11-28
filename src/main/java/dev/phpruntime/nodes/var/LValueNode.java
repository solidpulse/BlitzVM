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

    /** Current value, or Java {@code null} if undefined. */
    public abstract Object read(VirtualFrame frame, Object[] keys);

    /**
     * {@link #read} with the request context the bytecode instruction already read. Nodes that
     * do not use the context keep the default, which ignores it.
     */
    public Object read(VirtualFrame frame, Object[] keys, dev.phpruntime.PhpContext ctx) {
        return read(frame, keys);
    }

    /** Returns whether this lvalue is set (non-null) for {@code isset}. */
    public boolean isSet(VirtualFrame frame, Object[] keys) {
        Object v = read(frame, keys);
        return v != null && v != dev.phpruntime.runtime.PhpNull.INSTANCE;
    }

    public boolean isSet(VirtualFrame frame, Object[] keys, dev.phpruntime.PhpContext ctx) {
        return isSet(frame, keys);
    }

    /** Reads this lvalue for {@code ??}; may check {@code __isset} before {@code __get}. */
    public Object readIfSet(VirtualFrame frame, Object[] keys) {
        return read(frame, keys);
    }

    public Object readIfSet(VirtualFrame frame, Object[] keys, dev.phpruntime.PhpContext ctx) {
        return readIfSet(frame, keys);
    }

    public abstract void write(VirtualFrame frame, Object[] keys, Object value);

    public void write(VirtualFrame frame, Object[] keys, Object value, dev.phpruntime.PhpContext ctx) {
        write(frame, keys, value);
    }

    /** Append without exposing mutable storage. Arrays/properties retain immutable stores. */
    public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded) {
        Object current = read(frame, keys);
        String result = concat(dev.phpruntime.runtime.PhpOps.toStr(current == null
                ? dev.phpruntime.runtime.PhpNull.INSTANCE : current), suffix);
        write(frame, keys, result);
        return result;
    }

    public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded,
                               dev.phpruntime.PhpContext ctx) {
        Object current = read(frame, keys, ctx);
        String result = concat(dev.phpruntime.runtime.PhpOps.toStr(current == null
                ? dev.phpruntime.runtime.PhpNull.INSTANCE : current), suffix);
        write(frame, keys, result, ctx);
        return result;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String concat(String left, String right) {
        return left.concat(right);
    }

    /**
     * Obtains (or creates) a {@link PhpReference} cell backing this lvalue so writes to the
     * returned cell mutate this variable, array element, or property in place.
     */
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        throw referenceError(false);
    }

    public PhpReference obtainCell(VirtualFrame frame, Object[] keys, dev.phpruntime.PhpContext ctx) {
        return obtainCell(frame, keys);
    }

    /**
     * Binds this lvalue directly to {@code cell} (for {@code $lhs = &$rhs} and {@code foreach (... as &$v)}).
     */
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        throw referenceError(true);
    }

    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell, dev.phpruntime.PhpContext ctx) {
        bindCell(frame, keys, cell);
    }

    /**
     * For an index path rooted in a by-reference hooked property: the cell the get hook returned,
     * so an indirect write updates the referenced value instead of assigning the property.
     */
    public PhpReference indirectCell(VirtualFrame frame, Object[] keys) {
        return null;
    }

    public PhpReference indirectCell(VirtualFrame frame, Object[] keys, dev.phpruntime.PhpContext ctx) {
        return indirectCell(frame, keys);
    }

    /**
     * Joins the pieces of an error message behind a boundary. Concatenation compiled into guest
     * code is wasted on HotSpot and not allowed at all in native-image runtime compilation.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    protected static String msg(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part);
        }
        return sb.toString();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private dev.phpruntime.runtime.PhpError referenceError(boolean assignment) {
        return new dev.phpruntime.runtime.PhpError(
                (assignment ? "Cannot assign by reference to " : "Cannot take reference to ") + describe(), this);
    }

    public abstract void unset(VirtualFrame frame, Object[] keys);

    public void unset(VirtualFrame frame, Object[] keys, dev.phpruntime.PhpContext ctx) {
        unset(frame, keys);
    }

    /** Name used in diagnostics, e.g. "$x". */
    public abstract String describe();

    /** The rvalue read on evaluated keys: the value, or null with php's undefined warning. */
    public Object readValue(VirtualFrame frame, Object[] keys) {
        Object v = read(frame, keys);
        if (v == null) {
            return undefined();
        }
        return v;
    }

    public Object readValue(VirtualFrame frame, Object[] keys, dev.phpruntime.PhpContext ctx) {
        return readValue(frame, keys);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    protected Object undefined() {
        dev.phpruntime.PhpContext.get(this).warning("Undefined variable " + describe(), line());
        return dev.phpruntime.runtime.PhpNull.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    protected Object undefined(dev.phpruntime.PhpContext ctx) {
        ctx.warning("Undefined variable " + describe(), line());
        return dev.phpruntime.runtime.PhpNull.INSTANCE;
    }
}
