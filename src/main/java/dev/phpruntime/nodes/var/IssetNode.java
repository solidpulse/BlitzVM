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
                if (!ir.isSet(frame)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (targets.length > 1) {
            b.beginPhpAnd();
        }
        for (PhpNode t : targets) {
            if (t instanceof LocalVarNode lv) {
                b.emitIssetVar(e.accessor(lv), e.info(lv));
            } else if (t instanceof IndexReadNode ir) {
                ir.emitIsset(e);
            } else if (t instanceof IndexLValueNode il) {
                il.emitRead(e, 2);
            } else if (t instanceof LValueNode) {
                throw e.unsupported("isset of " + t.getClass().getSimpleName());
            } else {
                b.emitLoadConstant(true);
            }
        }
        if (targets.length > 1) {
            b.endPhpAnd();
        }
    }
}
