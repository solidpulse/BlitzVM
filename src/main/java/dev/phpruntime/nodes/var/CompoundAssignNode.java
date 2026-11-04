package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

/** {@code target op= value}, including {@code ??=} which only evaluates value when needed. */
public final class CompoundAssignNode extends PhpNode {

    @Child private LValueNode target;
    @Child private PhpNode value;
    private final PhpArith.Op op;

    public CompoundAssignNode(LValueNode target, PhpArith.Op op, PhpNode value) {
        this.target = target;
        this.op = op;
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] keys = target.evalKeys(frame);
        Object current = target.read(frame, keys);
        if (op == PhpArith.Op.COALESCE) {
            if (current != null && current != PhpNull.INSTANCE) {
                return current;
            }
            Object v = value.execute(frame);
            target.write(frame, keys, PhpOps.copyOnAssign(v));
            return v;
        }
        if (current == null) {
            current = PhpNull.INSTANCE;
        }
        Object result = applyFast(current, value.execute(frame));
        target.write(frame, keys, result);
        return result;
    }

    private Object applyFast(Object a, Object b) {
        if (op == PhpArith.Op.ADD && a instanceof Long x && b instanceof Long y) {
            long r = x + y;
            if (((x ^ r) & (y ^ r)) >= 0) {
                return r;
            }
        }
        return PhpArith.apply(op, a, b);
    }
}
