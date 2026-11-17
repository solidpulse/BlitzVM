package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** A double-quoted string with embedded variables: "Hello $name". */
public final class InterpolateNode extends PhpNode {

    @Children private final PhpNode[] parts;

    public InterpolateNode(PhpNode[] parts) {
        this.parts = parts;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] values = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = parts[i].execute(frame);
        }
        try {
            return join(values);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }

    @TruffleBoundary
    private static String join(Object[] values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(PhpOps.toStr(v));
        }
        return sb.toString();
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginInterpolate();
        for (PhpNode p : parts) {
            e.expr(p);
        }
        e.builder().endInterpolate();
    }
}
