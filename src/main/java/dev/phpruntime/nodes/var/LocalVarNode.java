package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * A function-local variable stored in a Truffle frame slot. Ints and floats are stored
 * in long and double slots rather than as boxed objects, so a loop that keeps a number
 * in a variable does not allocate. The slot's tag records which of the two it is, so
 * PHP's int/float distinction is kept exactly. A variable that holds both is stored
 * boxed; see {@link VarKind}.
 */
public final class LocalVarNode extends LValueNode {

    private final int slot;
    private final String name;
    private final VarKind kind;

    public LocalVarNode(int slot, String name, VarKind kind) {
        this.slot = slot;
        this.name = name;
        this.kind = kind;
    }

    /** A second node for the same variable, e.g. the read in {@code $x = $x + 1}. */
    public LocalVarNode copyForRead() {
        return new LocalVarNode(slot, name, kind).at(line());
    }

    public boolean holdsLong(VirtualFrame frame) {
        return frame.isLong(slot);
    }

    public boolean holdsDouble(VirtualFrame frame) {
        return frame.isDouble(slot);
    }

    public long getLong(VirtualFrame frame) {
        return frame.getLong(slot);
    }

    public double getDouble(VirtualFrame frame) {
        return frame.getDouble(slot);
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        return frame.getObject(slot);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = read(frame, NO_KEYS);
        return v == null ? undefined() : v;
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (kind.mixed() && frame.isObject(slot) && frame.getObject(slot) instanceof Long l) {
            return l; // a variable that has held both ints and floats stores them boxed
        }
        throw new UnexpectedResultException(execute(frame));
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        if (kind.mixed() && frame.isObject(slot) && frame.getObject(slot) instanceof Double d) {
            return d;
        }
        throw new UnexpectedResultException(execute(frame));
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        if (value instanceof Long l) {
            writeLong(frame, keys, l);
        } else if (value instanceof Double d) {
            writeDouble(frame, keys, d);
        } else {
            frame.setObject(slot, value);
        }
    }

    @Override
    public void writeLong(VirtualFrame frame, Object[] keys, long value) {
        if (kind.longBoxed()) {
            frame.setObject(slot, value);
        } else {
            frame.setLong(slot, value);
        }
    }

    @Override
    public void writeDouble(VirtualFrame frame, Object[] keys, double value) {
        if (kind.doubleBoxed()) {
            frame.setObject(slot, value);
        } else {
            frame.setDouble(slot, value);
        }
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        frame.setObject(slot, null);
    }

    @Override
    public String describe() {
        return "$" + name;
    }
}
