package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/**
 * {@code $lhs = &$rhs}: binds {@code lhs} directly to the {@link PhpReference} cell of {@code rhs}.
 */
public final class AssignRefNode extends PhpNode {

    @Child private LValueNode target;
    @Child private PhpNode value;

    public AssignRefNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpReference cell = resolveCell(frame);
        Object[] targetKeys = target.evalKeys(frame);
        target.bindCell(frame, targetKeys, cell);
        return cell.getValue() == null ? PhpNull.INSTANCE : cell.getValue();
    }

    @Override
    public Object executeForRef(VirtualFrame frame) {
        PhpReference cell = resolveCell(frame);
        Object[] targetKeys = target.evalKeys(frame);
        target.bindCell(frame, targetKeys, cell);
        return cell;
    }

    private PhpReference resolveCell(VirtualFrame frame) {
        if (value instanceof LocalVarNode lv && "this".equals(lv.varName())) {
            Object thisVal = lv.execute(frame);
            return new PhpReference(thisVal);
        }
        if (value instanceof LValueNode rhsLv) {
            return rhsLv.obtainCell(frame, rhsLv.evalKeys(frame));
        }
        Object raw = value.executeForRef(frame);
        if (raw instanceof PhpReference ref) {
            ref.ensureDefined();
            return ref;
        }
        PhpContext.get(this).notice("Only variables should be assigned by reference", line());
        return new PhpReference(PhpOps.copyOnAssign(raw));
    }
}
