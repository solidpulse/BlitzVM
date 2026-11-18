package dev.phpruntime.nodes.var;

import dev.phpruntime.nodes.PhpNode;

/** {@code .=}: statement execution leaves the append storage unflattened. */
public final class StringAppendNode extends PhpNode {
    @Child private LValueNode target;
    @Child private PhpNode value;

    public StringAppendNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emitAppend(e, true);
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emitAppend(e, false);
    }

    private void emitAppend(dev.phpruntime.bytecode.BytecodeEmitter e, boolean resultNeeded) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target)) {
            e.place(target, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_APPEND, resultNeeded ? 1 : 0, () -> e.expr(value));
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("append to " + target.getClass().getSimpleName());
        }
        e.builder().beginAppendVar(e.accessor(lv), e.info(lv), resultNeeded);
        e.expr(value);
        e.builder().endAppendVar();
    }
}
