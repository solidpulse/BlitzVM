package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/** {@code echo a, b;}, inline HTML and {@code <?= ?>} all compile to this. */
public final class EchoNode extends PhpNode {

    @Children private final PhpNode[] values;

    public EchoNode(PhpNode[] values) {
        this.values = values;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        for (PhpNode v : values) {
            e.builder().beginEcho();
            e.expr(v);
            e.builder().endEcho();
        }
        e.builder().endBlock();
    }
}
