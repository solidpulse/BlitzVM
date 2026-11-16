package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpNull;

/** {@code isset($a, $b['k'], static::C['k'])}: true only if every operand exists and is not null. Never warns. */
public final class IssetNode extends PhpNode {

    @Children private final PhpNode[] targets;

    public IssetNode(PhpNode[] targets) {
        this.targets = targets;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (PhpNode t : targets) {
            if (t instanceof LValueNode lv) {
                if (!lv.isSet(frame, lv.evalKeys(frame))) {
                    return false;
                }
            } else if (t instanceof IndexReadNode ir) {
                Object v = ir.readIfSet(frame);
                if (v == null || v == PhpNull.INSTANCE) {
                    return false;
                }
            }
        }
        return true;
    }
}
