package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpNull;

/**
 * Anonymous closure or arrow function creation expression.
 * Evaluates captured values at creation time (by value or by reference cell) and wraps them
 * with the enclosing {@code $this} and called class scope into a {@code Closure} {@link dev.phpruntime.runtime.PhpObject}.
 */
public final class ClosureNode extends PhpNode {

    private static final Object[] NO_KEYS = new Object[0];

    private final RootCallTarget callTarget;
    private final boolean bindThis;
    private final int outerThisSlot;
    private final int outerCalledClassSlot;
    private final int outerTraitSelfSlot;
    @CompilationFinal private int lexicalClassId = -1;
    @Children private final LValueNode[] captureReads;
    @CompilationFinal(dimensions = 1) private final boolean[] warnIfUndefined;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;

    public ClosureNode(RootCallTarget callTarget, boolean bindThis, int outerThisSlot, int outerCalledClassSlot,
                       LValueNode[] captureReads, boolean[] warnIfUndefined, boolean[] byRef) {
        this(callTarget, bindThis, outerThisSlot, outerCalledClassSlot, -1, captureReads, warnIfUndefined, byRef);
    }

    public ClosureNode inClass(int lexicalClassId) {
        this.lexicalClassId = lexicalClassId;
        return this;
    }

    public ClosureNode(RootCallTarget callTarget, boolean bindThis, int outerThisSlot, int outerCalledClassSlot,
                       int outerTraitSelfSlot, LValueNode[] captureReads, boolean[] warnIfUndefined, boolean[] byRef) {
        this.callTarget = callTarget;
        this.bindThis = bindThis;
        this.outerThisSlot = outerThisSlot;
        this.outerCalledClassSlot = outerCalledClassSlot;
        this.outerTraitSelfSlot = outerTraitSelfSlot;
        this.captureReads = captureReads;
        this.warnIfUndefined = warnIfUndefined;
        this.byRef = byRef;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginNewClosure(new dev.phpruntime.bytecode.PhpBytecodeRootNode.ClosureSpec(callTarget, byRef),
                bindThis, outerTraitSelfSlot >= 0, lexicalClassId);
        if (bindThis && outerThisSlot >= 0) {
            e.loadSlotOrNull(outerThisSlot);
        } else {
            b.emitLoadConstant(PhpNull.INSTANCE);
        }
        e.loadSlotOrNull(outerCalledClassSlot);
        e.loadSlotOrNull(outerTraitSelfSlot);
        for (int i = 0; i < captureReads.length; i++) {
            emitCapture(e, captureReads[i], warnIfUndefined[i], byRef[i]);
        }
        b.endNewClosure();
    }

    private static void emitCapture(dev.phpruntime.bytecode.BytecodeEmitter e, LValueNode capture,
                                    boolean warn, boolean byRef) {
        var b = e.builder();
        if (capture instanceof LocalVarNode local) {
            if (byRef) {
                b.emitObtainVarCell(e.accessor(local), e.info(local));
            } else if (warn) {
                b.emitReadVar(e.accessor(local), e.info(local));
            } else {
                b.emitReadVarIfSet(e.accessor(local), e.info(local));
            }
            return;
        }
        if (!dev.phpruntime.bytecode.BytecodeEmitter.isPlace(capture)) {
            throw e.unsupported("closure capture of " + capture.getClass().getSimpleName());
        }
        int mode = byRef ? dev.phpruntime.bytecode.PhpBytecodeRootNode.P_CELL
                : (warn ? dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ
                : dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ_IF_SET);
        e.place(capture, mode, 0, null);
    }
}
