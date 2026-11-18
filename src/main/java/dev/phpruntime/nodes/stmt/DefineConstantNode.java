package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/** Top-level {@code const NAME = expr, ...;} declaration. */
public final class DefineConstantNode extends PhpNode {

    private final String[] names;
    @Children private final PhpNode[] values;

    public DefineConstantNode(String[] names, PhpNode[] values) {
        this.names = names;
        this.values = values;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        for (int i = 0; i < names.length; i++) {
            e.builder().beginDefineConst(names[i], line());
            e.expr(values[i]);
            e.builder().endDefineConst();
        }
        e.builder().endBlock();
    }
}
