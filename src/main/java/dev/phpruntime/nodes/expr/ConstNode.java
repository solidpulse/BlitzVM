package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** A literal: int, float, string, bool or null. */
public final class ConstNode extends PhpNode {

    private final Object value;

    public ConstNode(Object value) {
        this.value = value;
    }

    public Object value() {
        return value;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().emitLoadConstant(value);
    }
}
