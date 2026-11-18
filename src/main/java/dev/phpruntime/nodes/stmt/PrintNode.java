package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/** {@code print expr} (an expression returning 1) and {@code exit(expr)} / {@code die}. */
public final class PrintNode extends PhpNode {

    @Child private PhpNode value;
    private final boolean exit;

    public PrintNode(PhpNode value, boolean exit) {
        this.value = value;
        this.exit = exit;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (exit) {
            // ControlFlow.Exit is an unwind exception, so try/finally handlers do not run it:
            // PHP skips finally blocks on exit/die.
            b.beginExit(value != null);
            if (value != null) {
                e.expr(value);
            } else {
                b.emitLoadConstant(dev.phpruntime.runtime.PhpNull.INSTANCE);
            }
            b.endExit();
            return;
        }
        b.beginBlock();
        b.beginEcho();
        e.expr(value);
        b.endEcho();
        b.emitLoadConstant(1L);
        b.endBlock();
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (exit) {
            emit(e);
            return;
        }
        var b = e.builder();
        b.beginEcho();
        e.expr(value);
        b.endEcho();
    }
}
