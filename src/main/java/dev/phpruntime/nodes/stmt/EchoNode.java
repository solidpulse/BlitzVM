package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** {@code echo a, b;}, inline HTML and {@code <?= ?>} all compile to this. */
public final class EchoNode extends PhpNode {

    @Children private final PhpNode[] values;

    public EchoNode(PhpNode[] values) {
        this.values = values;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        for (PhpNode v : values) {
            Object o = v.execute(frame);
            if (o instanceof String s) {
                ctx.echo(s);
            } else {
                try {
                    ctx.echo(PhpOps.toStr(o));
                } catch (dev.phpruntime.runtime.PhpError e) {
                    throw e.at(this);
                }
            }
        }
        return null;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        for (PhpNode v : values) {
            e.builder().beginEcho();
            e.expr(v);
            e.builder().endEcho();
        }
        e.builder().endBlock();
    }
}
