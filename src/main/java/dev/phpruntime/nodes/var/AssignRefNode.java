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

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (value instanceof dev.phpruntime.nodes.call.CallNode
                || value instanceof dev.phpruntime.nodes.call.MethodCallNode
                || value instanceof dev.phpruntime.nodes.call.StaticMethodCallNode
                || value instanceof dev.phpruntime.nodes.call.CallValueNode) {
            // The bytecode call operations unwrap a by-reference return, so the AST's
            // executeForRef is the only path that binds the returned reference.
            throw e.unsupported("reference assignment from a call");
        }
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal cell = e.temp();
        b.beginStoreLocal(cell);
        if (value instanceof LocalVarNode lv && "this".equals(lv.varName())) {
            b.beginBoxRef();
            e.expr(lv);
            b.endBoxRef();
        } else if (value instanceof LValueNode rhs) {
            e.cell(rhs);
        } else {
            b.beginRefFromValue();
            e.expr(value);
            b.endRefFromValue();
        }
        b.endStoreLocal();
        e.bind(target, () -> b.emitLoadLocal(cell));
        b.beginRefValue();
        b.emitLoadLocal(cell);
        b.endRefValue();
        b.endBlock();
    }
}
