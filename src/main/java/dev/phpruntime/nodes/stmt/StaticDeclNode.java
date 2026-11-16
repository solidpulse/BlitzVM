package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/**
 * {@code static $a [= expr], $b [= expr];}.
 * Stores persistent per-request {@link PhpReference} cells in {@link PhpContext},
 * keyed per closure object (inside closures) or per root node (functions and methods).
 */
public final class StaticDeclNode extends PhpNode {

    private final boolean global;
    private final int closureObjSlot;
    @CompilationFinal(dimensions = 1) private final String[] names;
    @CompilationFinal(dimensions = 1) private final int[] slots;
    @CompilationFinal(dimensions = 1) private final int[] globalIds;
    @Children private final PhpNode[] initExprs;
    @CompilationFinal private int tableSlot = -1;
    @CompilationFinal private boolean included;

    public void dynamicScope(int tableSlot) { this.tableSlot = tableSlot; }
    public void includedScope(int tableSlot) { this.tableSlot = tableSlot; this.included = true; }

    public StaticDeclNode(boolean global, int closureObjSlot, String[] names, int[] slots, int[] globalIds, PhpNode[] initExprs) {
        this.global = global;
        this.closureObjSlot = closureObjSlot;
        this.names = names;
        this.slots = slots;
        this.globalIds = globalIds;
        this.initExprs = initExprs;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        if (global) {
            for (int i = 0; i < names.length; i++) {
                PhpReference cell = ctx.getStaticLocal(ctx, names[i]);
                if (cell == null) {
                    Object init = initExprs[i] != null ? PhpOps.copyOnAssign(initExprs[i].execute(frame)) : PhpNull.INSTANCE;
                    cell = ctx.initStaticLocal(ctx, names[i], init);
                }
                ctx.bindGlobalById(globalIds[i], cell);
            }
            return null;
        }
        Object owner = closureObjSlot >= 0 ? frame.getObject(closureObjSlot) : getRootNode();
        for (int i = 0; i < names.length; i++) {
            PhpReference cell = included ? null : ctx.getStaticLocal(owner, names[i]);
            if (cell == null) {
                Object init = initExprs[i] != null ? PhpOps.copyOnAssign(initExprs[i].execute(frame)) : PhpNull.INSTANCE;
                cell = included ? new PhpReference(init) : ctx.initStaticLocal(owner, names[i], init);
            }
            if (tableSlot >= 0) {
                dev.phpruntime.runtime.PhpSymbolTable table = (dev.phpruntime.runtime.PhpSymbolTable) frame.getObject(tableSlot);
                if (included) { table.bind(names[i], cell); }
                else { table.cells[slots[i]] = cell; }
            } else { frame.setObject(slots[i], cell); }
        }
        return null;
    }
}
