package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;

/** {@code list(...) = expr} or {@code [...] = expr}. */
public final class DestructureAssignNode extends PhpNode {

    @Child private DestructureLValueNode target;
    @Child private PhpNode value;
    @Child private LValueNode valueLValue;

    public DestructureAssignNode(DestructureLValueNode target, PhpNode value, LValueNode valueLValue) {
        this.target = target;
        this.value = value;
        this.valueLValue = target.hasAnyRef() ? valueLValue : null;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return target.assignFrom(frame, value, valueLValue);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal state = e.temp();
        b.beginStoreLocal(state);
        if (target.hasAnyRef() && valueLValue != null) {
            b.beginDestructureStartCell(valueLValue.describe());
            e.cell(valueLValue);
            b.endDestructureStartCell();
        } else {
            b.beginDestructureStartValue(target.hasAnyRef());
            e.exprForRef(value);
            b.endDestructureStartValue();
        }
        b.endStoreLocal();
        e.destructureSteps(target, state);
        b.beginDestructureResult();
        b.emitLoadLocal(state);
        b.endDestructureResult();
        b.endBlock();
    }
}
