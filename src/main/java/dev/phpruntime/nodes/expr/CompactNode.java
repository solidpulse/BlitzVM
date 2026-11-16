package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
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

    @Override @ExplodeLoop public Object execute(VirtualFrame frame) {
        PhpArray result = new PhpArray();
        for (int i = 0; i < args.length; i++) {
            if (reads[i] != null) {
                Object value = reads[i].read(frame, reads[i].evalKeys(frame));
                put(result, names[i], value);
            } else {
                Object value = args[i].execute(frame);
                collect(result, value, tableSlot < 0 ? null : (PhpSymbolTable) frame.getObject(tableSlot), i + 1);
            }
        }
        return result;
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
                        : cell == null ? null : cell.value;
            }
            put(result, name, value);
        } else {
            PhpContext.get(this).warning("compact(): Argument #" + argument + " must be string or array of strings, " + PhpOps.typeName(names) + " given", line());
        }
    }
}
