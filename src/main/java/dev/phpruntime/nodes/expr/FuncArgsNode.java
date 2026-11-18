package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.nodes.var.ScopedVarNode;
import dev.phpruntime.nodes.var.VarKind;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/**
 * {@code func_num_args()}, {@code func_get_args()}, and {@code func_get_arg($idx)}.
 * The count is the number of positional arguments passed, and each declared parameter's
 * position is its current value (php 8): a parameter the body assigned or unset reads that
 * value, while extra arguments past the declared parameters and the variadic part are the
 * passed values. Reads directly from the current {@link VirtualFrame}.
 */
public final class FuncArgsNode extends PhpNode {

    public enum Kind {
        NUM_ARGS("func_num_args"),
        GET_ARGS("func_get_args"),
        GET_ARG("func_get_arg");

        public final String phpName;

        Kind(String phpName) {
            this.phpName = phpName;
        }
    }

    private final Kind kind;
    private final int argOffset;
    private final String[] paramNames;
    private final int[] paramSlots;
    private final VarKind[] paramKinds;
    private final int normalParamCount;
    @Child private PhpNode indexExpr;
    /** Set by DynamicScopeLowering when the body keeps its variables in a symbol table. */
    private int tableSlot = -1;
    private LValueNode[] paramReads;

    public FuncArgsNode(Kind kind, int argOffset, String[] paramNames, int[] paramSlots,
                        VarKind[] paramKinds, int normalParamCount, PhpNode indexExpr) {
        this.kind = kind;
        this.argOffset = argOffset;
        this.paramNames = paramNames;
        this.paramSlots = paramSlots;
        this.paramKinds = paramKinds;
        this.normalParamCount = normalParamCount;
        this.indexExpr = indexExpr;
    }

    public Kind kind() {
        return kind;
    }

    public int argOffset() {
        return argOffset;
    }

    public int normalParamCount() {
        return normalParamCount;
    }

    public PhpNode indexExpr() {
        return indexExpr;
    }

    /** Called after parsing when the function got a symbol table; parameter reads go through it. */
    public void dynamicScope(int tableSlot) {
        this.tableSlot = tableSlot;
        this.paramReads = null;
    }

    private LValueNode paramRead(int i) {
        if (paramReads == null) {
            LValueNode[] reads = new LValueNode[paramSlots.length];
            for (int j = 0; j < paramSlots.length; j++) {
                reads[j] = tableSlot >= 0
                        ? new ScopedVarNode(tableSlot, paramSlots[j], paramNames[j])
                        : new LocalVarNode(paramSlots[j], paramNames[j], paramKinds[j]);
            }
            paramReads = reads;
        }
        return paramReads[i];
    }

    private static PhpError globalScope(Kind kind, Node location) {
        String message = kind.phpName + "(): Cannot be called from the global scope";
        return location == null ? new PhpError(message) : new PhpError(message, location);
    }

    /** Whether a trailing named-variadic group is not a positional argument. */
    private static int argumentCount(int argOffset, Object[] args) {
        int count = args.length - argOffset;
        if (count > 0 && args[args.length - 1] instanceof PhpFunctionRootNode.NamedVariadic) {
            count--;
        }
        return count;
    }

    private static Object passedValue(Object value) {
        if (value instanceof PhpReference ref) {
            value = ref.getValue();
        }
        return value == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(value);
    }

    /** The count of positional arguments past the hidden prefix slots. */
    public static long numArgs(int argOffset, Object[] args, Node location) {
        if (argOffset < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw globalScope(Kind.NUM_ARGS, location);
        }
        return argumentCount(argOffset, args);
    }

    /**
     * A copy of the arguments as PHP values. The first {@code normalCount} entries use
     * {@code current}, the parameters' current values ({@code null} when unset); arguments
     * past them are the passed values.
     */
    public static PhpArray getArgs(int argOffset, int normalCount, Object[] current, Object[] args, Node location) {
        if (argOffset < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw globalScope(Kind.GET_ARGS, location);
        }
        int count = argumentCount(argOffset, args);
        PhpArray arr = new PhpArray();
        for (int i = 0; i < count; i++) {
            arr.append(argumentAt(argOffset, normalCount, current, args, i));
        }
        return arr;
    }

    /** One argument by index with php's range errors. */
    public static Object getArg(int argOffset, int normalCount, Object[] current, Object[] args,
                                Object rawIndex, Node location) {
        if (argOffset < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw globalScope(Kind.GET_ARG, location);
        }
        long idx = PhpOps.toLong(rawIndex);
        int count = argumentCount(argOffset, args);
        if (idx < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            String message = Kind.GET_ARG.phpName
                    + "(): Argument #1 ($position) must be greater than or equal to 0";
            throw new PhpError("ValueError", message, location);
        }
        if (idx >= count) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            String message = Kind.GET_ARG.phpName
                    + "(): Argument #1 ($position) must be less than the number of the arguments passed"
                    + " to the currently executed function";
            throw new PhpError("ValueError", message, location);
        }
        return argumentAt(argOffset, normalCount, current, args, (int) idx);
    }

    private static Object argumentAt(int argOffset, int normalCount, Object[] current, Object[] args, int i) {
        if (i < normalCount && i < current.length) {
            Object value = current[i];
            return value == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(value);
        }
        return passedValue(args[argOffset + i]);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (argOffset < 0) {
            // Keep php's order: the global-scope error comes before the index expression.
            b.emitFuncArgsGlobal(kind);
            return;
        }
        if (kind == Kind.NUM_ARGS) {
            b.emitFuncArgs(kind, argOffset);
            return;
        }
        b.beginFuncArgsCurrent(kind, argOffset, normalParamCount);
        for (int i = normalParamCount - 1; i >= 0; i--) {
            b.beginArrayAppend();
        }
        b.emitNewArray();
        for (int i = 0; i < normalParamCount; i++) {
            e.rawRead(paramRead(i));
            b.endArrayAppend();
        }
        if (kind == Kind.GET_ARG) {
            e.expr(indexExpr);
        } else {
            b.emitLoadConstant(PhpNull.INSTANCE);
        }
        b.endFuncArgsCurrent();
    }
}
