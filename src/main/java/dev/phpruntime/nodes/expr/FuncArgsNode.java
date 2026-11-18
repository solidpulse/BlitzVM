package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;

/**
 * {@code func_num_args()}, {@code func_get_args()}, and {@code func_get_arg($idx)}.
 * Reads directly from the current {@link VirtualFrame} arguments past the hidden prefix slots.
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
    @Child private PhpNode indexExpr;

    public FuncArgsNode(Kind kind, int argOffset, PhpNode indexExpr) {
        this.kind = kind;
        this.argOffset = argOffset;
        this.indexExpr = indexExpr;
    }

    public Kind kind() {
        return kind;
    }

    public int argOffset() {
        return argOffset;
    }

    public PhpNode indexExpr() {
        return indexExpr;
    }

    private static PhpError globalScope(Kind kind, Node location) {
        String message = kind.phpName + "(): Cannot be called from the global scope";
        return location == null ? new PhpError(message) : new PhpError(message, location);
    }

    /** The count of positional arguments past the hidden prefix slots. */
    public static long numArgs(int argOffset, Object[] args, Node location) {
        if (argOffset < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw globalScope(Kind.NUM_ARGS, location);
        }
        return args.length - argOffset;
    }

    /** A copy of the arguments as PHP values, reference cells unwrapped. */
    public static PhpArray getArgs(int argOffset, Object[] args, Node location) {
        if (argOffset < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw globalScope(Kind.GET_ARGS, location);
        }
        int count = args.length - argOffset;
        PhpArray arr = new PhpArray();
        for (int i = 0; i < count; i++) {
            Object v = args[argOffset + i];
            if (v instanceof dev.phpruntime.runtime.PhpReference ref) {
                v = ref.getValue() == null ? dev.phpruntime.runtime.PhpNull.INSTANCE : ref.getValue();
            }
            arr.append(PhpOps.copyOnAssign(v));
        }
        return arr;
    }

    /** One argument by index with php's range errors, reference cells unwrapped. */
    public static Object getArg(int argOffset, Object[] args, Object rawIndex, Node location) {
        if (argOffset < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw globalScope(Kind.GET_ARG, location);
        }
        long idx = PhpOps.toLong(rawIndex);
        int count = args.length - argOffset;
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
        Object v = args[argOffset + (int) idx];
        if (v instanceof dev.phpruntime.runtime.PhpReference ref) {
            v = ref.getValue() == null ? dev.phpruntime.runtime.PhpNull.INSTANCE : ref.getValue();
        }
        return PhpOps.copyOnAssign(v);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return switch (kind) {
            case NUM_ARGS -> numArgs(argOffset, frame.getArguments(), this);
            case GET_ARGS -> getArgs(argOffset, frame.getArguments(), this);
            case GET_ARG -> getArg(argOffset, frame.getArguments(), indexExpr.execute(frame), this);
        };
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (argOffset < 0) {
            // Keep php's order: the global-scope error comes before the index expression.
            b.emitFuncArgsGlobal(kind);
            return;
        }
        if (kind == Kind.GET_ARG) {
            b.beginFuncGetArg(argOffset);
            e.expr(indexExpr);
            b.endFuncGetArg();
        } else {
            b.emitFuncArgs(kind, argOffset);
        }
    }
}
