package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/** Expressions evaluated in order (comma lists in for-conditions). */
public final class BlockValueNode extends PhpNode {

    @Children private final PhpNode[] exprs;

    public BlockValueNode(PhpNode[] exprs) {
        this.exprs = exprs;
    }
}
