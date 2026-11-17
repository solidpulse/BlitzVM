package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/** break N, continue N, return expr. */
public final class JumpNode extends PhpNode {

    public enum Kind { BREAK, CONTINUE, RETURN }

    private final Kind kind;
    private final int levels;
    private final boolean returnByRef;
    @Child private PhpNode value;

    public JumpNode(Kind kind, int levels, PhpNode value) {
        this(kind, levels, value, false);
    }

    public JumpNode(Kind kind, int levels, PhpNode value, boolean returnByRef) {
        this.kind = kind;
        this.levels = levels;
        this.value = value;
        this.returnByRef = returnByRef;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        switch (kind) {
            case BREAK -> throw new ControlFlow.Break(levels);
            case CONTINUE -> throw new ControlFlow.Continue(levels);
            default -> {
                if (value == null) {
                    throw new ControlFlow.Return(returnByRef ? new PhpReference(PhpNull.INSTANCE) : PhpNull.INSTANCE);
                }
                if (returnByRef) {
                    if (value instanceof LValueNode lv) {
                        throw new ControlFlow.Return(lv.obtainCell(frame, lv.evalKeys(frame)));
                    }
                    Object v = value.execute(frame);
                    throw new ControlFlow.Return(v instanceof PhpReference ref ? ref : new PhpReference(PhpOps.copyOnAssign(v)));
                }
                throw new ControlFlow.Return(value.execute(frame));
            }
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        switch (kind) {
            case BREAK -> e.emitBreak(levels, false);
            case CONTINUE -> e.emitBreak(levels, true);
            default -> {
                if (returnByRef) {
                    throw e.unsupported("return by reference");
                }
                var b = e.builder();
                if (value == null) {
                    e.emitFinallies();
                    b.beginReturn();
                    b.emitLoadConstant(dev.phpruntime.runtime.PhpNull.INSTANCE);
                    b.endReturn();
                } else if (!e.hasFinallies()) {
                    b.beginReturn();
                    e.expr(value);
                    b.endReturn();
                } else {
                    // PHP evaluates the return value before finally runs.
                    var result = e.temp();
                    b.beginStoreLocal(result);
                    e.expr(value);
                    b.endStoreLocal();
                    e.emitFinallies();
                    b.beginReturn();
                    b.emitLoadLocal(result);
                    b.endReturn();
                }
            }
        }
    }
}
