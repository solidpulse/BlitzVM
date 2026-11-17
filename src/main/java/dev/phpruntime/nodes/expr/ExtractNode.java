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
    private final boolean hasPrefix;

    public ExtractNode(PhpNode array, LValueNode arrayTarget, PhpNode flags, PhpNode prefix, int tableSlot) {
        this(array, arrayTarget, flags, prefix, tableSlot, prefix != null);
    }

    private ExtractNode(PhpNode array, LValueNode arrayTarget, PhpNode flags, PhpNode prefix, int tableSlot, boolean hasPrefix) {
        this.array = array;
        this.arrayTarget = arrayTarget;
        this.flags = flags;
        this.prefix = prefix;
        this.tableSlot = tableSlot;
        this.hasPrefix = hasPrefix;
    }

    /** A copy for bytecode: the operation evaluates the arguments and calls {@link #run}. */
    public ExtractNode headless() {
        return new ExtractNode(null, null, null, null, tableSlot, hasPrefix).at(line());
    }

    /**
     * Bytecode form. EXTR_REFS separates the array and writes it back into the argument
     * variable; only flags that cannot contain it are supported.
     */
    @Override public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (arrayTarget != null && flags != null && !withoutRefs(flags)) {
            throw e.unsupported("extract with EXTR_REFS");
        }
        var b = e.builder();
        b.beginExtract(new dev.phpruntime.bytecode.PhpBytecodeRootNode.ExtractSpec(this));
        e.loadTable(tableSlot);
        if (arrayTarget != null) {
            e.rawRead(arrayTarget);
        } else {
            e.expr(array);
        }
        if (flags != null) {
            e.expr(flags);
        } else {
            b.emitLoadConstant(0L);
        }
        if (prefix != null) {
            e.expr(prefix);
        } else {
            b.emitLoadConstant("");
        }
        b.endExtract();
    }

    private static boolean withoutRefs(PhpNode flags) {
        if (flags instanceof ConstNode c) {
            return c.value() instanceof Long l && (l & 256) == 0;
        }
        if (flags instanceof ReadConstantNode r) {
            String name = r.name();
            name = name.substring(name.lastIndexOf('\\') + 1);
            return name.startsWith("EXTR_") && !name.equals("EXTR_REFS");
        }
        return false;
    }

    /** The extraction on evaluated arguments (no write-back for EXTR_REFS: see {@link #emit}). */
    public Object run(Object table, Object value, Object rawFlags, Object rawPrefix) {
        long flag = checkFlags(rawFlags, rawPrefix);
        String pre = PhpOps.toStr(rawPrefix);
        if (!(value instanceof PhpArray input)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "extract(): Argument #1 ($array) must be of type array, " + PhpOps.typeName(value) + " given", this);
        }
        boolean refs = (flag & 256) != 0;
        if (refs) {
            input = input.forWrite();
        }
        return extract(input, (int) flag & 255, refs, pre, table instanceof PhpSymbolTable t ? t : null);
    }

    private long checkFlags(Object rawFlags, Object rawPrefix) {
        if (!(rawFlags == PhpNull.INSTANCE || rawFlags instanceof Long || rawFlags instanceof Double
                || rawFlags instanceof Boolean || rawFlags instanceof String s && PhpOps.isNumericString(s))) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "extract(): Argument #2 ($flags) must be of type int, " + PhpOps.typeName(rawFlags) + " given", this);
        }
        if (rawPrefix instanceof PhpArray || rawPrefix instanceof PhpObject) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "extract(): Argument #3 ($prefix) must be of type string, " + PhpOps.typeName(rawPrefix) + " given", this);
        }
        long flag = PhpOps.toLong(rawFlags);
        validate(flag, PhpOps.toStr(rawPrefix));
        return flag;
    }

    @Override public Object execute(VirtualFrame frame) {
        Object[] keys = arrayTarget == null ? null : arrayTarget.evalKeys(frame);
        Object value = arrayTarget == null ? array.execute(frame) : arrayTarget.read(frame, keys);
        Object rawFlags = flags == null ? 0L : flags.execute(frame);
        Object rawPrefix = prefix == null ? "" : prefix.execute(frame);
        if (!(rawFlags == PhpNull.INSTANCE || rawFlags instanceof Long || rawFlags instanceof Double
                || rawFlags instanceof Boolean || rawFlags instanceof String s && PhpOps.isNumericString(s))) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "extract(): Argument #2 ($flags) must be of type int, " + PhpOps.typeName(rawFlags) + " given", this);
        }
        if (rawPrefix instanceof PhpArray || rawPrefix instanceof PhpObject) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "extract(): Argument #3 ($prefix) must be of type string, " + PhpOps.typeName(rawPrefix) + " given", this);
        }
        long flag = PhpOps.toLong(rawFlags);
        String pre = PhpOps.toStr(rawPrefix);
        validate(flag, pre);
        if (!(value instanceof PhpArray input)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
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
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.VALUE_ERROR, "extract(): Argument #2 ($flags) must be a valid extract type", this);
        }
        if ((type == 2 || type == 3 || type == 4 || type == 5) && !hasPrefix) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.VALUE_ERROR, "extract(): Argument #3 ($prefix) is required when using this extract type", this);
        }
        if ((type == 2 || type == 3 || type == 4 || type == 5) && !prefix.isEmpty() && !identifier(prefix)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
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
                    : table.cell(original, false) != null && table.cell(original, false).getValue() != null;
            String name = original;
            switch (type) {
                case 1 -> { if (exists || original.equals("this")) { continue; } }
                case 2 -> { if (exists || original.equals("this")) { name = prefix + "_" + original; } }
                case 3 -> { if (original.isEmpty()) { continue; } name = prefix + "_" + original; }
                case 4 -> { if (!identifier(original) || original.equals("this")) { name = prefix + "_" + original; } }
                case 5 -> {
                    if (exists) { name = prefix + "_" + original; }
                    else if (table == null ? !ctx.isReservedGlobal(original) : !table.layout.locals().containsKey(original)) { continue; }
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
                Object value = entry.getValue() instanceof PhpReference ref ? ref.getValue() : entry.getValue();
                value = PhpOps.copyOnAssign(value);
                if (table == null) { ctx.writeExtractedGlobal(name, value); }
                else { table.cell(name, true).setValue(value); }
            }
            count++;
        }
        return count;
    }
}
