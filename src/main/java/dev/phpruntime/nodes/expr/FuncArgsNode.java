package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
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

        final String phpName;

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

    @Override
    public Object execute(VirtualFrame frame) {
        if (argOffset < 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(kind.phpName + "(): Cannot be called from the global scope", this);
        }
        Object[] args = frame.getArguments();
        int count = args.length - argOffset;
        return switch (kind) {
            case NUM_ARGS -> (long) count;
            case GET_ARGS -> {
                PhpArray arr = new PhpArray();
                for (int i = 0; i < count; i++) {
                    Object v = args[argOffset + i];
                    if (v instanceof dev.phpruntime.runtime.PhpReference ref) {
                        v = ref.getValue() == null ? dev.phpruntime.runtime.PhpNull.INSTANCE : ref.getValue();
                    }
                    arr.append(PhpOps.copyOnAssign(v));
                }
                yield arr;
            }
            case GET_ARG -> {
                long idx = PhpOps.toLong(indexExpr.execute(frame));
                if (idx < 0) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("ValueError", kind.phpName + "(): Argument #1 ($position) must be greater than or equal to 0", this);
                }
                if (idx >= count) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("ValueError", kind.phpName + "(): Argument #1 ($position) must be less than the number of the arguments passed to the currently executed function", this);
                }
                Object v = args[argOffset + (int) idx];
                if (v instanceof dev.phpruntime.runtime.PhpReference ref) {
                    v = ref.getValue() == null ? dev.phpruntime.runtime.PhpNull.INSTANCE : ref.getValue();
                }
                yield PhpOps.copyOnAssign(v);
            }
        };
    }
}
