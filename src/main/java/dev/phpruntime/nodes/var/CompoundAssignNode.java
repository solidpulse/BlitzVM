package dev.phpruntime.nodes.var;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArith;

/** {@code target op= value}, including {@code ??=} which only evaluates value when needed. */
public final class CompoundAssignNode extends PhpNode {

    @Child private LValueNode target;
    @Child private PhpNode value;
    private final PhpArith.Op op;

    public CompoundAssignNode(LValueNode target, PhpArith.Op op, PhpNode value) {
        this.target = target;
        this.op = op;
        this.value = value;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target) && op != PhpArith.Op.COALESCE) {
            e.place(target, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_COMPOUND, op.ordinal(), () -> e.expr(value), value);
            return;
        }
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target) && op == PhpArith.Op.COALESCE) {
            e.coalescePlace(target, () -> e.expr(value));
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("compound assign to " + target.getClass().getSimpleName());
        }
        var b = e.builder();
        if (op == PhpArith.Op.COALESCE) {
            b.beginCoalesce();
            b.emitReadVarIfSet(e.accessor(lv), e.info(lv));
            b.beginWriteVar(e.accessor(lv), e.info(lv));
            e.expr(value);
            b.endWriteVar();
            b.endCoalesce();
            return;
        }
        b.beginCompoundVar(e.accessor(lv), e.info(lv), op);
        e.expr(value);
        b.endCompoundVar();
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target) && op != PhpArith.Op.COALESCE) {
            e.place(target, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_COMPOUND_DISCARD,
                    op.ordinal(), () -> e.expr(value), value);
            return;
        }
        emit(e);
    }
}
