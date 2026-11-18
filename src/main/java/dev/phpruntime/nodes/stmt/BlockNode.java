package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

public final class BlockNode extends PhpNode {

    @Children private final PhpNode[] statements;

    public BlockNode(PhpNode[] statements) {
        this.statements = statements;
    }

    public PhpNode[] statements() {
        return statements;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        for (PhpNode s : statements) {
            e.stmt(s);
        }
        e.builder().endBlock();
    }
}
