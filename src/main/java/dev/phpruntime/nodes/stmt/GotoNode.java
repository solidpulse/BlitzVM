package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/** {@code goto label;}: the emitter turns it into a pc assignment. */
public final class GotoNode extends PhpNode {

    private final int labelId;

    public GotoNode(int labelId) {
        this.labelId = labelId;
    }

    public int labelId() {
        return labelId;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.gotoJump(labelId);
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emit(e);
    }
}
