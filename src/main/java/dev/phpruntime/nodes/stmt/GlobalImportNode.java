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

    public GlobalImportNode(int[] slots, int[] globalIds) {
        this.slots = slots;
        this.globalIds = globalIds;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        for (int i = 0; i < slots.length; i++) {
            frame.setObject(slots[i], ctx.globalRefById(globalIds[i]));
        }
        return null;
    }
}
