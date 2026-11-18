package dev.phpruntime.nodes.var;

import dev.phpruntime.nodes.PhpNode;

/**
 * {@code $lhs = &$rhs}: binds {@code lhs} directly to the {@link dev.phpruntime.runtime.PhpReference}
 * cell of {@code rhs}.
 */
public final class AssignRefNode extends PhpNode {

    @Child private LValueNode target;
    @Child private PhpNode value;

    public AssignRefNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
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
            e.exprForRef(value);
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
