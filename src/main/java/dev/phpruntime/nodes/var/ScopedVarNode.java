package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpSymbolTable;

/** Statically named local in a dynamic function: a direct indexed cell access, not a hash lookup. */
public final class ScopedVarNode extends LValueNode {
    private final int tableSlot;
    private final int slot;
    private final String name;

    public ScopedVarNode(int tableSlot, int slot, String name) {
        this.tableSlot = tableSlot;
        this.slot = slot;
        this.name = name;
    }

    private PhpSymbolTable table(VirtualFrame frame) { return (PhpSymbolTable) frame.getObject(tableSlot); }

    @Override public Object read(VirtualFrame frame, Object[] keys) {
        if (name.equals("this")) {
            Object value = table(frame).thisValue;
            if (value == null || value == PhpNull.INSTANCE) { throw new PhpError("Using $this when not in object context", this); }
            return value;
        }
        PhpReference cell = table(frame).cells[slot];
        return cell == null ? null : cell.value;
    }

    private void writable() {
        if (name.equals("this")) { throw new PhpError("Cannot re-assign $this", this); }
    }

    @Override public void write(VirtualFrame frame, Object[] keys, Object value) {
        writable();
        PhpReference[] cells = table(frame).cells;
        PhpReference cell = cells[slot];
        if (cell == null) { cells[slot] = new PhpReference(value); }
        else { cell.value = value; }
    }

    @Override public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        writable();
        PhpReference[] cells = table(frame).cells;
        PhpReference cell = cells[slot];
        if (cell == null) { cells[slot] = cell = new PhpReference(PhpNull.INSTANCE); }
        else if (cell.value == null) { cell.value = PhpNull.INSTANCE; }
        return cell;
    }

    @Override public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        writable();
        table(frame).cells[slot] = cell;
    }

    @Override public void unset(VirtualFrame frame, Object[] keys) {
        if (name.equals("this")) { throw new PhpError("Cannot unset $this", this); }
        table(frame).cells[slot] = null;
    }

    @Override public String describe() { return "$" + name; }
}
