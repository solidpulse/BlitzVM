package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpSymbolTable;

public final class GetDefinedVarsNode extends PhpNode {
    private final int tableSlot;
    public GetDefinedVarsNode(int tableSlot) { this.tableSlot = tableSlot; }
    @Override public Object execute(VirtualFrame frame) {
        return tableSlot < 0 ? PhpContext.get(this).buildGlobalsSnapshot()
                : ((PhpSymbolTable) frame.getObject(tableSlot)).snapshot();
    }

    @Override public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginGetDefinedVars();
        e.loadTable(tableSlot);
        e.builder().endGetDefinedVars();
    }
}
