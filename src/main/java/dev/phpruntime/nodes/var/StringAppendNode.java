package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;

/** {@code .=}: statement execution leaves the append storage unflattened. */
public final class StringAppendNode extends PhpNode {
    @Child private LValueNode target;
    @Child private PhpNode value;

    public StringAppendNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override public Object execute(VirtualFrame frame) {
        return append(frame, true);
    }

    @Override public void executeVoid(VirtualFrame frame) { append(frame, false); }

    private String append(VirtualFrame frame, boolean resultNeeded) {
        Object[] keys = target.evalKeys(frame);
        try {
            // PHP evaluates the RHS before fetching the current LHS (including rebinding).
            Object suffix = value.execute(frame);
            return target.appendString(frame, keys, suffix instanceof String s ? s : PhpOps.toStr(suffix), resultNeeded);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }
}
