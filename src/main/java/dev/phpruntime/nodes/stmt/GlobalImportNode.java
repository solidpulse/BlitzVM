package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;

/**
 * {@code global $a, $b;}: binds each local frame slot to the global variable's
 * {@link dev.phpruntime.runtime.PhpReference} cell in {@link PhpContext}.
 */
public final class GlobalImportNode extends PhpNode {

    @CompilationFinal(dimensions = 1) private final int[] slots;
    @CompilationFinal(dimensions = 1) private final int[] globalIds;
    @CompilationFinal private int tableSlot = -1;
    @Children private final dev.phpruntime.nodes.var.LValueNode[] targets;
    @Children private final PhpNode[] names;

    public void dynamicScope(int tableSlot) { this.tableSlot = tableSlot; }

    public GlobalImportNode(int[] slots, int[] globalIds) {
        this.slots = slots;
        this.globalIds = globalIds;
        targets = null;
        names = null;
    }

    public GlobalImportNode(dev.phpruntime.nodes.var.LValueNode[] targets, PhpNode[] names) {
        this.targets = targets;
        this.names = names;
        slots = null;
        globalIds = null;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        if (targets != null) {
            for (int i = 0; i < targets.length; i++) {
                String name = dev.phpruntime.runtime.PhpOps.toStr(names[i].execute(frame));
                targets[i].bindCell(frame, new Object[]{name}, ctx.globalSymbolRef(name));
            }
            return null;
        }
        for (int i = 0; i < slots.length; i++) {
            if (tableSlot >= 0) {
                ((dev.phpruntime.runtime.PhpSymbolTable) frame.getObject(tableSlot)).cells[slots[i]] = ctx.globalRefById(globalIds[i]);
            } else { frame.setObject(slots[i], ctx.globalRefById(globalIds[i])); }
        }
        return null;
    }
}
