package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** PHP 8.5's statement-level {@code (void) expr}: evaluate it and discard the value. */
public final class VoidCastNode extends PhpNode {

    @Child private PhpNode value;

    public VoidCastNode(PhpNode value) {
        this.value = value;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        // Only the statement form is legal; a value context is rejected by the parser.
        e.stmt(value);
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.stmt(value);
    }
}
