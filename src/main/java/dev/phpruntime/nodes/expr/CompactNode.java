package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.*;

/** Literal arguments compile to indexed local reads. Only computed names take the symbol-table path. */
public final class CompactNode extends PhpNode {
    @Children private final PhpNode[] args;
    @Children private final LValueNode[] reads;
    @CompilationFinal(dimensions = 1) private final String[] names;
    private final int tableSlot;

    public CompactNode(PhpNode[] args, LValueNode[] reads, String[] names, int tableSlot) {
        this.args = args;
        this.reads = reads;
        this.names = names;
        this.tableSlot = tableSlot;
    }

    /** A copy for bytecode: arguments arrive evaluated (literal names as raw variable reads). */
    public CompactNode headless() {
        return new CompactNode(new PhpNode[args.length], new LValueNode[reads.length], names, tableSlot).at(line());
    }

    /** {@code values[i]}: the raw read of literal name i ({@code UNDEFINED} if unset), else argument i. */
    @TruffleBoundary
    public Object build(Object table, Object[] values) {
        PhpArray result = new PhpArray();
        PhpSymbolTable symbols = table instanceof PhpSymbolTable t ? t : null;
        for (int i = 0; i < values.length; i++) {
            if (names[i] != null) {
                Object v = values[i];
                put(result, names[i], v == dev.phpruntime.bytecode.PhpBytecodeRootNode.UNDEFINED ? null : v);
            } else {
                collect(result, values[i], symbols, i + 1);
            }
        }
        return result;
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
        b.endCompact();
    }

    @TruffleBoundary private void put(PhpArray result, String name, Object value) {
        if (value == null) {
            if (!name.equals("this")) { PhpContext.get(this).warning("compact(): Undefined variable $" + name, line()); }
        }
        else { result.put(name, PhpOps.copyOnAssign(value)); }
    }

    @TruffleBoundary private void collect(PhpArray result, Object names, PhpSymbolTable table, int argument) {
        if (names instanceof PhpArray array) {
            for (Object name : array.values()) { collect(result, name, table, argument); }
        } else if (names instanceof String name) {
            Object value;
            if (table == null) { value = PhpContext.get(this).readGlobalSymbol(name); }
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
}
