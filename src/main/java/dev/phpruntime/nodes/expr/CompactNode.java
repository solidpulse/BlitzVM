package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.*;

/**
 * Literal arguments compile to indexed local reads. Computed names in a function without a symbol
 * table resolve against raw reads of all its locals, passed as extra operands, so one
 * {@code compact($pieces)} does not move every variable of the function into a table. Only file
 * scope and functions that need a table anyway take the symbol-table path.
 */
public final class CompactNode extends PhpNode {
    @Children private final PhpNode[] args;
    @Children private final LValueNode[] reads;
    @CompilationFinal(dimensions = 1) private final String[] names;
    private int tableSlot;
    /** The function's locals for computed names, set when its scope is built; null on the table path. */
    @CompilationFinal(dimensions = 1) private String[] localNames;
    private LValueNode[] localReads;

    public CompactNode(PhpNode[] args, LValueNode[] reads, String[] names, int tableSlot) {
        this.args = args;
        this.reads = reads;
        this.names = names;
        this.tableSlot = tableSlot;
    }

    /** A copy for bytecode: arguments arrive evaluated (literal names as raw variable reads). */
    public CompactNode headless() {
        CompactNode copy = new CompactNode(new PhpNode[args.length], new LValueNode[reads.length], names, tableSlot);
        copy.localNames = localNames;
        return copy.at(line());
    }

    /** Computed names resolve through the function's symbol table at {@code slot}. */
    public void useTable(int slot) {
        this.tableSlot = slot;
    }

    /** Computed names resolve against these locals (the function has no symbol table). */
    public void bindLocals(String[] localNames, LValueNode[] localReads) {
        this.localNames = localNames;
        this.localReads = localReads;
    }

    /**
     * {@code values[i]}: the raw read of literal name i ({@code UNDEFINED} if unset), else argument i;
     * then, with {@link #localNames}, the raw read of each local.
     */
    @TruffleBoundary
    public Object build(Object table, Object[] values) {
        PhpArray result = new PhpArray();
        PhpSymbolTable symbols = table instanceof PhpSymbolTable t ? t : null;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (name != null) {
                // The literal-name fast path, without the per-name boundary of put().
                Object value = defined(values[i]);
                if (value != null) {
                    result.put(name, PhpOps.copyOnAssign(value));
                } else if (!name.equals("this")) {
                    PhpContext.get(this).warning("compact(): Undefined variable $" + name, line());
                }
            } else {
                collect(result, values[i], symbols, values, i + 1);
            }
        }
        return result;
    }

    private static Object defined(Object raw) {
        return raw == dev.phpruntime.bytecode.PhpBytecodeRootNode.UNDEFINED ? null : raw;
    }

    @Override public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginCompact(new dev.phpruntime.bytecode.PhpBytecodeRootNode.CompactSpec(this));
        e.loadTable(tableSlot);
        for (int i = 0; i < args.length; i++) {
            if (reads[i] != null) {
                e.rawRead(reads[i]);
            } else {
                e.expr(args[i]);
            }
        }
        if (localReads != null) {
            for (LValueNode local : localReads) {
                e.rawRead(local);
            }
        }
        b.endCompact();
    }

    @TruffleBoundary private void put(PhpArray result, String name, Object value) {
        if (value == null) {
            if (!name.equals("this")) { PhpContext.get(this).warning("compact(): Undefined variable $" + name, line()); }
        }
        else { result.put(name, PhpOps.copyOnAssign(value)); }
    }

    @TruffleBoundary private void collect(PhpArray result, Object names, PhpSymbolTable table, Object[] values, int argument) {
        if (names instanceof PhpArray array) {
            for (Object name : array.values()) { collect(result, name, table, values, argument); }
        } else if (names instanceof String name) {
            Object value;
            if (localNames != null) { value = local(name, values); }
            else if (table == null) { value = PhpContext.get(this).readGlobalSymbol(name); }
            else {
                PhpReference cell = table.cell(name, false);
                value = name.equals("this") ? table.thisValue == PhpNull.INSTANCE ? null : table.thisValue
                        : cell == null ? null : cell.getValue();
            }
            put(result, name, value);
        } else {
            PhpContext.get(this).warning("compact(): Argument #" + argument + " must be string or array of strings, " + PhpOps.typeName(names) + " given", line());
        }
    }

    private Object local(String name, Object[] values) {
        for (int j = 0; j < localNames.length; j++) {
            if (localNames[j].equals(name)) {
                return defined(values[names.length + j]);
            }
        }
        return null;
    }
}
