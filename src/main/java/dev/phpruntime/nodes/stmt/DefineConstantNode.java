package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** Top-level {@code const NAME = expr, ...;} declaration. */
public final class DefineConstantNode extends PhpNode {

    private final String[] names;
    @Children private final PhpNode[] values;

    public DefineConstantNode(String[] names, PhpNode[] values) {
        this.names = names;
        this.values = values;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        for (int i = 0; i < names.length; i++) {
            Object val = PhpOps.copyOnAssign(values[i].execute(frame));
            ctx.defineConstant(names[i], val, line());
        }
        return null;
    }
}
