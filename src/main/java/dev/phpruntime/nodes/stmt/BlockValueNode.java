package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/** Expressions evaluated in order (comma lists in for-conditions). */
public final class BlockValueNode extends PhpNode {

    @Children private final PhpNode[] exprs;

    public BlockValueNode(PhpNode[] exprs) {
        this.exprs = exprs;
    }

    /** Every expression but the last for its effect; the block's value is the last one's. */
    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        for (int i = 0; i < exprs.length - 1; i++) {
            e.stmt(exprs[i]);
        }
        e.expr(exprs[exprs.length - 1]);
        e.builder().endBlock();
    }
}
