package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;

/** {@code unset($a, $b['k'])}. */
public final class UnsetNode extends PhpNode {

    @Children private final LValueNode[] targets;

    public UnsetNode(LValueNode[] targets) {
        this.targets = targets;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (LValueNode t : targets) {
            t.unset(frame, t.evalKeys(frame));
        }
        return null;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        emitTargets(e);
        e.builder().endBlock();
    }

    private void emitTargets(dev.phpruntime.bytecode.BytecodeEmitter e) {
        for (LValueNode t : targets) {
            if (t instanceof LocalVarNode lv && !"this".equals(lv.varName())) {
                e.builder().emitUnsetVar(e.accessor(lv), e.info(lv));
            } else if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(t)) {
                e.place(t, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_UNSET, 0, null);
            } else {
                throw e.unsupported("unset of " + t.getClass().getSimpleName());
            }
        }
    }
}
