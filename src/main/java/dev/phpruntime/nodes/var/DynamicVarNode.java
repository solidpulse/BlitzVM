package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.*;

/** A computed variable name, or a statically named variable in an included local-scope file. */
public final class DynamicVarNode extends LValueNode {
    @Child private PhpNode nameExpr;
    private final int tableSlot;
    private final boolean literal;
    private record Resolution(PhpScopeLayout layout, String name, int slot) {}
    @CompilationFinal private Resolution resolution;

    public DynamicVarNode(PhpNode nameExpr, int tableSlot) {
        this(nameExpr, tableSlot, false);
    }

    public DynamicVarNode(PhpNode nameExpr, int tableSlot, boolean literal) {
        this.nameExpr = nameExpr;
        this.tableSlot = tableSlot;
        this.literal = literal;
    }

    public int tableSlot() { return tableSlot; }
    @Override public Object[] evalKeys(VirtualFrame frame) { return new Object[]{PhpOps.toStr(nameExpr.execute(frame))}; }

    private PhpSymbolTable table(VirtualFrame frame) { return (PhpSymbolTable) frame.getObject(tableSlot); }

    private int slot(PhpSymbolTable table, String name) {
        Resolution hit = resolution;
        if (hit != null && hit.layout() == table.layout && hit.name().equals(name)) { return hit.slot(); }
        return resolve(table.layout, name);
    }

    @TruffleBoundary
    private int resolve(PhpScopeLayout layout, String name) {
        Integer slot = layout.locals().get(name);
        if (slot == null) { return -1; }
        if (resolution == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            var lock = getLock();
            lock.lock();
            try {
                if (resolution == null) { resolution = new Resolution(layout, name, slot); }
            } finally { lock.unlock(); }
        }
        return slot;
    }

    @Override public Object read(VirtualFrame frame, Object[] keys) {
        String name = (String) keys[0];
        if (tableSlot < 0) { return PhpContext.get(this).readGlobalSymbol(name); }
        PhpSymbolTable table = table(frame);
        if (name.equals("this")) {
            if (literal && (table.thisValue == null || table.thisValue == PhpNull.INSTANCE)) {
                throw new PhpError("Using $this when not in object context", this);
            }
            return table.thisValue == PhpNull.INSTANCE ? null : table.thisValue;
        }
        int slot = slot(table, name);
        PhpReference cell = slot >= 0 ? table.cells[slot] : table.cell(name, false);
        return cell == null ? null : cell.getValue();
    }

    @Override public boolean isSet(VirtualFrame frame, Object[] keys) {
        if (literal && keys[0].equals("this")) {
            Object value = table(frame).thisValue;
            return value != null && value != PhpNull.INSTANCE;
        }
        return (literal || !keys[0].equals("this")) && super.isSet(frame, keys);
    }

    @Override public Object readIfSet(VirtualFrame frame, Object[] keys) {
        if (literal && keys[0].equals("this")) {
            Object value = table(frame).thisValue;
            return value == PhpNull.INSTANCE ? null : value;
        }
        return !literal && keys[0].equals("this") ? null : read(frame, keys);
    }

    private void writable(String name) {
        if (name.equals("this")) { throw new PhpError("Cannot re-assign $this", this); }
    }

    @Override public void write(VirtualFrame frame, Object[] keys, Object value) {
        String name = (String) keys[0];
        writable(name);
        if (tableSlot < 0) { PhpContext.get(this).writeExtractedGlobal(name, value); return; }
        PhpSymbolTable table = table(frame);
        int slot = slot(table, name);
        if (slot >= 0) {
            PhpReference cell = table.cells[slot];
            if (cell == null) { table.cells[slot] = PhpReference.local(value); }
            else { cell.setValue(value); }
        } else { table.cell(name, true).setValue(value); }
    }

    @Override public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        String name = (String) keys[0];
        writable(name);
        if (tableSlot < 0) { return PhpContext.get(this).globalSymbolRef(name); }
        PhpSymbolTable table = table(frame);
        int slot = slot(table, name);
        PhpReference cell;
        if (slot >= 0) {
            cell = table.cells[slot];
            if (cell == null) { table.cells[slot] = cell = new PhpReference(null); }
        } else { cell = table.cell(name, true); }
        if (cell.getValue() == null) { cell.setValue(PhpNull.INSTANCE); }
        return cell.alias();
    }

    @Override public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        String name = (String) keys[0];
        writable(name);
        if (tableSlot < 0) { PhpContext.get(this).bindExtractedGlobal(name, cell); return; }
        PhpSymbolTable table = table(frame);
        cell.alias();
        int slot = slot(table, name);
        if (slot >= 0) { table.cells[slot] = cell; }
        else { table.bind(name, cell); }
    }

    @Override public void unset(VirtualFrame frame, Object[] keys) {
        String name = (String) keys[0];
        if (name.equals("this")) {
            if (literal) { throw new PhpError("Cannot unset $this", this); }
            return;
        }
        writable(name);
        if (tableSlot < 0) { PhpContext.get(this).unsetGlobalSymbol(name); }
        else { table(frame).unset(name); }
    }

    @Override public Object execute(VirtualFrame frame) {
        Object[] keys = evalKeys(frame);
        Object value = read(frame, keys);
        if (value == null) {
            PhpContext.get(this).warning("Undefined variable $" + keys[0], line());
            return PhpNull.INSTANCE;
        }
        return value;
    }

    @Override public String describe() { return "${...}"; }
}
