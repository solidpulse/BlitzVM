package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.*;

/** extract is caller-scope-sensitive, so it is a node rather than a context-only builtin. */
public final class ExtractNode extends PhpNode {
    @Child private PhpNode array;
    @Child private LValueNode arrayTarget;
    @Child private PhpNode flags;
    @Child private PhpNode prefix;
    private final int tableSlot;

    public ExtractNode(PhpNode array, LValueNode arrayTarget, PhpNode flags, PhpNode prefix, int tableSlot) {
        this.array = array;
        this.arrayTarget = arrayTarget;
        this.flags = flags;
        this.prefix = prefix;
        this.tableSlot = tableSlot;
    }

    @Override public Object execute(VirtualFrame frame) {
        Object[] keys = arrayTarget == null ? null : arrayTarget.evalKeys(frame);
        Object value = arrayTarget == null ? array.execute(frame) : arrayTarget.read(frame, keys);
        long flag = flags == null ? 0 : PhpOps.toLong(flags.execute(frame));
        String pre = prefix == null ? "" : PhpOps.toStr(prefix.execute(frame));
        validate(flag, pre);
        if (!(value instanceof PhpArray input)) {
            throw new PhpError(PhpError.TYPE_ERROR, "extract(): Argument #1 ($array) must be of type array, " + PhpOps.typeName(value) + " given", this);
        }
        boolean refs = (flag & 256) != 0;
        if (refs) {
            input = input.forWrite();
            if (arrayTarget != null) { arrayTarget.write(frame, keys, input); }
        }
        return extract(input, (int) flag & 255, refs, pre,
                tableSlot < 0 ? null : (PhpSymbolTable) frame.getObject(tableSlot));
    }

    public static boolean identifier(String name) {
        if (name.isEmpty() || !start(name.charAt(0))) { return false; }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!start(c) && !(c >= '0' && c <= '9')) { return false; }
        }
        return true;
    }

    private static boolean start(char c) { return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= 128 && c <= 255; }

    private void validate(long flags, String prefix) {
        int type = (int) flags & 255;
        if (type > 6) {
            throw new PhpError(PhpError.VALUE_ERROR, "extract(): Argument #2 ($flags) must be a valid extract type", this);
        }
        if ((type == 2 || type == 3 || type == 4 || type == 5) && this.prefix == null) {
            throw new PhpError(PhpError.VALUE_ERROR, "extract(): Argument #3 ($prefix) is required when using this extract type", this);
        }
        if (!prefix.isEmpty() && !identifier(prefix)) {
            throw new PhpError(PhpError.VALUE_ERROR, "extract(): Argument #3 ($prefix) must be a valid identifier", this);
        }
    }

    @TruffleBoundary
    private long extract(PhpArray array, int type, boolean refs, String prefix, PhpSymbolTable table) {
        PhpContext ctx = PhpContext.get(this);
        long count = 0;
        for (var entry : array.rawEntries()) {
            String original = PhpOps.toStr(entry.getKey());
            boolean exists = table == null ? ctx.isGlobalDefined(original)
                    : original.equals("this") ? table.thisValue != null && table.thisValue != PhpNull.INSTANCE
                    : table.cell(original, false) != null && table.cell(original, false).value != null;
            String name = original;
            switch (type) {
                case 1 -> { if (exists) { continue; } }
                case 2 -> { if (exists) { name = prefix + "_" + original; } }
                case 3 -> { if (original.isEmpty()) { continue; } name = prefix + "_" + original; }
                case 4 -> { if (!identifier(original)) { name = prefix + "_" + original; } }
                case 5 -> {
                    if (exists) { name = prefix + "_" + original; }
                    else if (table == null || !table.layout.locals().containsKey(original)) { continue; }
                }
                case 6 -> { if (!exists) { continue; } }
                default -> { }
            }
            if (!identifier(name)) { continue; }
            if (name.equals("this")) { throw new PhpError("Cannot re-assign $this", this); }
            if (refs) {
                PhpReference cell = array.obtainElementRef(entry.getKey());
                if (table == null) { ctx.bindExtractedGlobal(name, cell); }
                else { table.bind(name, cell); }
            } else {
                Object value = entry.getValue() instanceof PhpReference ref ? ref.value : entry.getValue();
                value = PhpOps.copyOnAssign(value);
                if (table == null) { ctx.writeExtractedGlobal(name, value); }
                else { table.cell(name, true).value = value; }
            }
            count++;
        }
        return count;
    }
}
