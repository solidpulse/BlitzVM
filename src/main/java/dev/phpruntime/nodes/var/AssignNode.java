package dev.phpruntime.nodes.var;

import dev.phpruntime.nodes.PhpNode;

/** {@code target = value}. Arrays are shared copy-on-write, giving PHP value semantics. */
public final class AssignNode extends PhpNode {

    @Child private LValueNode target;
    @Child private PhpNode value;

    public AssignNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target)) {
            e.place(target, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_WRITE, 0, () -> e.expr(value));
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("assign to " + target.getClass().getSimpleName());
        }
        e.builder().beginWriteVar(e.accessor(lv), e.info(lv));
        e.expr(value);
        e.builder().endWriteVar();
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target)) {
            emit(e);
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("assign to " + target.getClass().getSimpleName());
        }
        e.builder().beginStoreVar(e.accessor(lv), e.info(lv));
        e.expr(value);
        e.builder().endStoreVar();
    }
}
