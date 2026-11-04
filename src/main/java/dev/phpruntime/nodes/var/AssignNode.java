package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** {@code target = value}. Arrays are shared copy-on-write, giving PHP value semantics. */
public final class AssignNode extends PhpNode {

    @Child private LValueNode target;
    @Child private PhpNode value;

    public AssignNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] keys = target.evalKeys(frame);
        Object v = value.execute(frame);
        target.write(frame, keys, PhpOps.copyOnAssign(v));
        return v;
    }
}
