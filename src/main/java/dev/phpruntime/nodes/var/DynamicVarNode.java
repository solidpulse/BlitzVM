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
        this(nameExpr, tableSlot, literal, false);
    }

    /** Bytecode copy: keys are (table, name); the table is null when {@code tableSlot < 0} (globals). */
    private final boolean headless;

    private DynamicVarNode(PhpNode nameExpr, int tableSlot, boolean literal, boolean headless) {
        this.nameExpr = nameExpr;
        this.tableSlot = tableSlot;
        this.literal = literal;
        this.headless = headless;
    }

    public int tableSlot() { return tableSlot; }
    public PhpNode nameExpr() { return nameExpr; }

    /** A copy for bytecode places: no name expression, keys (table, name). */
    public DynamicVarNode headless() { return new DynamicVarNode(null, tableSlot, literal, true).at(line()); }

    @Override public Object[] evalKeys(VirtualFrame frame) { return new Object[]{PhpOps.toStr(nameExpr.execute(frame))}; }

    private PhpSymbolTable table(VirtualFrame frame, Object[] keys) {
        return (PhpSymbolTable) (headless ? keys[0] : frame.getObject(tableSlot));
    }

    private Object nameKey(Object[] keys) { return keys[headless ? 1 : 0]; }

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
        String name = (String) nameKey(keys);
        if (tableSlot < 0) { return PhpContext.get(this).readGlobalSymbol(name); }
        PhpSymbolTable table = table(frame, keys);
        if (name.equals("this")) {
            if (literal && (table.thisValue == null || table.thisValue == PhpNull.INSTANCE)) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Using $this when not in object context", this);
            }
            return table.thisValue == PhpNull.INSTANCE ? null : table.thisValue;
        }
        int slot = slot(table, name);
        PhpReference cell = slot >= 0 ? table.cells[slot] : table.cell(name, false);
        return cell == null ? null : cell.getValue();
    }

    @Override public boolean isSet(VirtualFrame frame, Object[] keys) {
        if (literal && isThisKey(nameKey(keys))) {
            Object value = table(frame, keys).thisValue;
            return value != null && value != PhpNull.INSTANCE;
        }
        return (literal || !isThisKey(nameKey(keys))) && super.isSet(frame, keys);
    }

    @Override public Object readIfSet(VirtualFrame frame, Object[] keys) {
        if (literal && isThisKey(nameKey(keys))) {
            Object value = table(frame, keys).thisValue;
            return value == PhpNull.INSTANCE ? null : value;
        }
        return !literal && isThisKey(nameKey(keys)) ? null : read(frame, keys);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean isThisKey(Object key) {
        return key.equals("this");
    }

    private void writable(String name) {
        if (name.equals("this")) { throw new PhpError("Cannot re-assign $this", this); }
    }

    @Override public void write(VirtualFrame frame, Object[] keys, Object value) {
        String name = (String) nameKey(keys);
        writable(name);
        if (tableSlot < 0) { PhpContext.get(this).writeExtractedGlobal(name, value); return; }
        PhpSymbolTable table = table(frame, keys);
        int slot = slot(table, name);
        if (slot >= 0) {
            PhpReference cell = table.cells[slot];
            if (cell == null) { table.cells[slot] = PhpReference.local(value); }
            else { cell.setValue(value); }
        } else { table.cell(name, true).setValue(value); }
    }

    @Override public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        String name = (String) nameKey(keys);
        writable(name);
        if (tableSlot < 0) { return PhpContext.get(this).globalSymbolRef(name); }
        PhpSymbolTable table = table(frame, keys);
        int slot = slot(table, name);
        PhpReference cell;
        if (slot >= 0) {
            cell = table.cells[slot];
            if (cell == null) { table.cells[slot] = cell = new PhpReference(null); }
        } else { cell = table.cell(name, true); }
        cell.ensureDefined();
        return cell.alias();
    }

    @Override public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded) {
        String name = (String) nameKey(keys);
        writable(name);
        if (tableSlot < 0) {
            if (name.equals("GLOBALS")) {
                return PhpContext.get(this).globalSymbolRef(name).appendString(suffix, resultNeeded);
            }
            return PhpContext.get(this).appendGlobalById(PhpContext.get(this).language().globalId(name), suffix, resultNeeded);
        }
        PhpSymbolTable table = table(frame, keys);
        int slot = slot(table, name);
        PhpReference cell;
        if (slot >= 0) {
            cell = table.cells[slot];
            if (cell == null) { table.cells[slot] = cell = PhpReference.local(null); }
        } else { cell = table.cell(name, true); }
        return cell.appendString(suffix, resultNeeded);
    }

    @Override public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        String name = (String) nameKey(keys);
        writable(name);
        if (tableSlot < 0) { PhpContext.get(this).bindExtractedGlobal(name, cell); return; }
        PhpSymbolTable table = table(frame, keys);
        cell.alias();
        int slot = slot(table, name);
        if (slot >= 0) { table.cells[slot] = cell; }
        else { table.bind(name, cell); }
    }

    @Override public void unset(VirtualFrame frame, Object[] keys) {
        String name = (String) nameKey(keys);
        if (name.equals("this")) {
            if (literal) { throw new PhpError("Cannot unset $this", this); }
            return;
        }
        writable(name);
        if (tableSlot < 0) { PhpContext.get(this).unsetGlobalSymbol(name); }
        else { table(frame, keys).unset(name); }
    }

    @Override public Object execute(VirtualFrame frame) {
        return readValue(frame, evalKeys(frame));
    }

    @Override public Object readValue(VirtualFrame frame, Object[] keys) {
        Object value = read(frame, keys);
        if (value == null) {
            warnUndefined(nameKey(keys));
            return PhpNull.INSTANCE;
        }
        return value;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void warnUndefined(Object key) {
        PhpContext.get(this).warning("Undefined variable $" + key, line());
    }

    @Override public String describe() { return "${...}"; }

    @Override public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.place(this, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ, 0, null);
    }
}
