package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

public final class GetDefinedVarsNode extends PhpNode {
    private final int tableSlot;
    public GetDefinedVarsNode(int tableSlot) { this.tableSlot = tableSlot; }

    @Override public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginGetDefinedVars();
        e.loadTable(tableSlot);
        e.builder().endGetDefinedVars();
    }
}
