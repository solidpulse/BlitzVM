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
    @Children private final PhpNode[] initExprs;

    public StaticDeclNode(boolean global, int closureObjSlot, String[] names, int[] slots, PhpNode[] initExprs) {
        this.global = global;
        this.closureObjSlot = closureObjSlot;
        this.names = names;
        this.slots = slots;
        this.initExprs = initExprs;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        if (global) {
            for (int i = 0; i < names.length; i++) {
                if (ctx.getStaticLocal(ctx, names[i]) == null) {
                    Object init = initExprs[i] != null ? PhpOps.copyOnAssign(initExprs[i].execute(frame)) : PhpNull.INSTANCE;
                    ctx.initStaticLocal(ctx, names[i], init);
                    if (ctx.readGlobal(names[i]) == null) {
                        ctx.writeGlobal(names[i], init);
                    }
                }
            }
            return null;
        }
        Object owner = closureObjSlot >= 0 ? frame.getObject(closureObjSlot) : getRootNode();
        for (int i = 0; i < names.length; i++) {
            PhpReference cell = ctx.getStaticLocal(owner, names[i]);
            if (cell == null) {
                Object init = initExprs[i] != null ? PhpOps.copyOnAssign(initExprs[i].execute(frame)) : PhpNull.INSTANCE;
                cell = ctx.initStaticLocal(owner, names[i], init);
            }
            frame.setObject(slots[i], cell);
        }
        return null;
    }
}
