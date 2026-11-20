package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** A literal: int, float, string, bool or null. */
public final class ConstNode extends PhpNode {

    private final Object value;
    private final String constantName;

    public ConstNode(Object value) {
        this(value, null);
    }

    public ConstNode(Object value, String constantName) {
        this.value = value;
        this.constantName = constantName;
    }

    public Object value() {
        return value;
    }

    /** The global constant this literal was folded from, or null for a written literal. */
    public String constantName() {
        return constantName;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().emitLoadConstant(value);
    }
}
