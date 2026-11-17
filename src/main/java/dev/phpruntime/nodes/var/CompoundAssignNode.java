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
        dev.phpruntime.runtime.PhpReference nested = op != PhpArith.Op.COALESCE && target instanceof IndexLValueNode index
                && index.isNested() && index.usesArrayAccess(frame, keys) ? index.obtainCell(frame, keys) : null;
        Object current = nested != null ? nested.getValue()
                : op == PhpArith.Op.COALESCE ? target.readIfSet(frame, keys) : target.read(frame, keys);
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
        Object result;
        try {
            result = applyFast(current, value.execute(frame));
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
        if (nested != null) { nested.setValue(result); }
        else { target.write(frame, keys, result); }
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

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target) && op != PhpArith.Op.COALESCE) {
            e.place(target, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_COMPOUND, op.ordinal(), () -> e.expr(value));
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("compound assign to " + target.getClass().getSimpleName());
        }
        var b = e.builder();
        if (op == PhpArith.Op.COALESCE) {
            b.beginCoalesce();
            b.emitReadVarIfSet(e.accessor(lv), e.info(lv));
            b.beginWriteVar(e.accessor(lv), e.info(lv));
            e.expr(value);
            b.endWriteVar();
            b.endCoalesce();
            return;
        }
        b.beginCompoundVar(e.accessor(lv), e.info(lv), op);
        e.expr(value);
        b.endCompoundVar();
    }
}
