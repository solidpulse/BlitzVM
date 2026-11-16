package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpStringBuffer;
import dev.phpruntime.runtime.PhpOps;

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

    public int slot() {
        return slot;
    }

    public String varName() {
        return name;
    }

    public VarKind kind() {
        return kind;
    }

    /** A second node for the same variable, e.g. the read in {@code $x = $x + 1}. */
    public LocalVarNode copyForRead() {
        return new LocalVarNode(slot, name, kind).at(line());
    }

    public boolean holdsLong(VirtualFrame frame) {
        return !kind.isCell() && frame.isLong(slot);
    }

    public boolean holdsDouble(VirtualFrame frame) {
        return !kind.isCell() && frame.isDouble(slot);
    }

    public long getLong(VirtualFrame frame) {
        return frame.getLong(slot);
    }

    public double getDouble(VirtualFrame frame) {
        return frame.getDouble(slot);
    }

    private PhpReference getOrMigrateCell(VirtualFrame frame) {
        if (frame.isObject(slot)) {
            Object obj = frame.getObject(slot);
            if (obj == null || obj instanceof PhpReference) {
                return (PhpReference) obj;
            }
            PhpReference ref = new PhpReference(kind.isAppendTarget() ? PhpStringBuffer.flatten(obj) : obj);
            frame.setObject(slot, ref);
            return ref;
        }
        if (frame.isLong(slot)) {
            PhpReference ref = new PhpReference(frame.getLong(slot));
            frame.setObject(slot, ref);
            return ref;
        }
        if (frame.isDouble(slot)) {
            PhpReference ref = new PhpReference(frame.getDouble(slot));
            frame.setObject(slot, ref);
            return ref;
        }
        return null;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        if (kind.isCell()) {
            PhpReference ref = getOrMigrateCell(frame);
            return ref == null ? null : ref.getValue();
        }
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        Object value = frame.getObject(slot);
        return kind.isAppendTarget() ? PhpStringBuffer.flatten(value) : value;
    }

    @Override
    public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded) {
        if (kind.isCell()) {
            return super.appendString(frame, keys, suffix, resultNeeded);
        }
        Object current = frame.isObject(slot) ? frame.getObject(slot)
                : frame.isLong(slot) ? frame.getLong(slot) : frame.isDouble(slot) ? frame.getDouble(slot) : null;
        PhpStringBuffer buffer;
        if (current instanceof PhpStringBuffer existing) { buffer = existing; }
        else {
            buffer = new PhpStringBuffer(current instanceof String s ? s
                    : PhpOps.toStr(current == null ? PhpNull.INSTANCE : current));
            frame.setObject(slot, buffer);
        }
        buffer.append(suffix);
        return resultNeeded ? buffer.asString() : null;
    }

    public PhpReference readOrCreateCell(VirtualFrame frame) {
        PhpReference ref = getOrMigrateCell(frame);
        if (ref == null) {
            ref = new PhpReference(PhpNull.INSTANCE);
            frame.setObject(slot, ref);
        } else if (ref.getValue() == null) {
            ref.setValue(PhpNull.INSTANCE);
        }
        return ref;
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        if ("this".equals(name)) {
            throw new dev.phpruntime.runtime.PhpError("Cannot re-assign $this", this);
        }
        if (!kind.isCell()) {
            kind.markCell();
        }
        return readOrCreateCell(frame);
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        if ("this".equals(name)) {
            throw new dev.phpruntime.runtime.PhpError("Cannot re-assign $this", this);
        }
        if (!kind.isCell()) {
            kind.markCell();
        }
        frame.setObject(slot, cell);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = read(frame, NO_KEYS);
        return v == null ? undefined() : v;
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (kind.isCell()) {
            PhpReference ref = getOrMigrateCell(frame);
            if (ref != null && ref.getValue() instanceof Long l) {
                return l;
            }
            throw new UnexpectedResultException(execute(frame));
        }
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
        if (kind.isCell()) {
            PhpReference ref = getOrMigrateCell(frame);
            if (ref != null && ref.getValue() instanceof Double d) {
                return d;
            }
            throw new UnexpectedResultException(execute(frame));
        }
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
        if (kind.isCell()) {
            writeCell(frame, value);
            return;
        }
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
        if (kind.isCell()) {
            writeCell(frame, value);
            return;
        }
        if (kind.longBoxed()) {
            frame.setObject(slot, value);
        } else {
            frame.setLong(slot, value);
        }
    }

    @Override
    public void writeDouble(VirtualFrame frame, Object[] keys, double value) {
        if (kind.isCell()) {
            writeCell(frame, value);
            return;
        }
        if (kind.doubleBoxed()) {
            frame.setObject(slot, value);
        } else {
            frame.setDouble(slot, value);
        }
    }

    private void writeCell(VirtualFrame frame, Object value) {
        PhpReference ref = getOrMigrateCell(frame);
        if (ref == null) {
            frame.setObject(slot, new PhpReference(value));
        } else {
            ref.setValue(value);
        }
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        if (kind.isCell()) {
            frame.setObject(slot, new PhpReference(null));
        } else {
            frame.setObject(slot, null);
        }
    }

    @Override
    public String describe() {
        return "$" + name;
    }
}
