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
            try {
                PhpContext.get(this).echo(PhpOps.toStr(v));
            } catch (dev.phpruntime.runtime.PhpError e) {
                throw e.at(this);
            }
        }
        if (exit) {
            throw new ControlFlow.Exit();
        }
        return 1L;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (exit) {
            // ControlFlow.Exit is an unwind exception, so try/finally handlers do not run it:
            // PHP skips finally blocks on exit/die.
            b.beginExit(value != null);
            if (value != null) {
                e.expr(value);
            } else {
                b.emitLoadConstant(dev.phpruntime.runtime.PhpNull.INSTANCE);
            }
            b.endExit();
            return;
        }
        b.beginBlock();
        b.beginEcho();
        e.expr(value);
        b.endEcho();
        b.emitLoadConstant(1L);
        b.endBlock();
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (exit) {
            emit(e);
            return;
        }
        var b = e.builder();
        b.beginEcho();
        e.expr(value);
        b.endEcho();
    }
}
