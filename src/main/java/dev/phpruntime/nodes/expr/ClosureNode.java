package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.GlobalVarNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

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
    @Children private final LValueNode[] captureReads;
    @CompilationFinal(dimensions = 1) private final boolean[] warnIfUndefined;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;

    public ClosureNode(RootCallTarget callTarget, boolean bindThis, int outerThisSlot, int outerCalledClassSlot,
                       LValueNode[] captureReads, boolean[] warnIfUndefined, boolean[] byRef) {
        this.callTarget = callTarget;
        this.bindThis = bindThis;
        this.outerThisSlot = outerThisSlot;
        this.outerCalledClassSlot = outerCalledClassSlot;
        this.captureReads = captureReads;
        this.warnIfUndefined = warnIfUndefined;
        this.byRef = byRef;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        Object boundThis = bindThis && outerThisSlot >= 0 ? frame.getObject(outerThisSlot) : PhpNull.INSTANCE;
        PhpClass calledClass = outerCalledClassSlot >= 0 ? (PhpClass) frame.getObject(outerCalledClassSlot) : null;
        Object[] caps = new Object[captureReads.length];
        for (int i = 0; i < captureReads.length; i++) {
            if (byRef[i]) {
                if (captureReads[i] instanceof LocalVarNode lv) {
                    caps[i] = lv.readOrCreateCell(frame);
                } else if (captureReads[i] instanceof GlobalVarNode gv) {
                    caps[i] = ctx.globalRef(gv.name());
                } else {
                    Object v = captureReads[i].read(frame, NO_KEYS);
                    caps[i] = new PhpReference(v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v));
                }
            } else {
                Object v = warnIfUndefined[i]
                        ? captureReads[i].execute(frame)
                        : captureReads[i].read(frame, NO_KEYS);
                caps[i] = v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v);
            }
        }
        return ctx.newClosure(PhpClosure.closure(callTarget, boundThis, calledClass, caps));
    }
}
