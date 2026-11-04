package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** {@code print expr} (an expression returning 1) and {@code exit(expr)} / {@code die}. */
public final class PrintNode extends PhpNode {

    @Child private PhpNode value;
    private final boolean exit;

    public PrintNode(PhpNode value, boolean exit) {
        this.value = value;
        this.exit = exit;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = value == null ? null : value.execute(frame);
        if (v != null && !(exit && v instanceof Long)) {
            PhpContext.get(this).echo(PhpOps.toStr(v));
        }
        if (exit) {
            throw new ControlFlow.Exit();
        }
        return 1L;
    }
}
