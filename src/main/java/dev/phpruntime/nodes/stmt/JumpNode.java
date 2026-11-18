package dev.phpruntime.nodes.stmt;

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

    /**
     * {@code return $x;} in a function declared {@code function &f()}: the cell of an lvalue,
     * else the value in a new cell (a returned reference stays itself), as the AST did.
     */
    private void emitReturnRef(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        var result = e.temp();
        b.beginStoreLocal(result);
        if (value instanceof LValueNode lv
                && !(lv instanceof dev.phpruntime.nodes.var.LocalVarNode local && "this".equals(local.varName()))) {
            e.cell(lv);
        } else {
            b.beginBoxRefCopy();
            e.exprForRef(value);
            b.endBoxRefCopy();
        }
        b.endStoreLocal();
        e.emitFinallies();
        b.beginReturn();
        b.emitLoadLocal(result);
        b.endReturn();
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        switch (kind) {
            case BREAK -> e.emitBreak(levels, false);
            case CONTINUE -> e.emitBreak(levels, true);
            default -> {
                var b = e.builder();
                if (returnByRef && value != null) {
                    emitReturnRef(e);
                    return;
                }
                if (value == null) {
                    e.emitFinallies();
                    b.beginReturn();
                    b.emitLoadConstant(PhpNull.INSTANCE);
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
