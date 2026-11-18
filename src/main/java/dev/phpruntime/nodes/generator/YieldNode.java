package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpGenerator;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/** One yield site. Its state and intermediate operands are in hidden per-invocation slots. */
public final class YieldNode extends PhpNode {
    @Child private PhpNode key;
    @Child private PhpNode value;
    private final int stateSlot;
    private final int generatorSlot;
    /** The enclosing function is declared {@code function &f()}: yield the value's cell. */
    private final boolean byRef;

    public YieldNode(int generatorSlot, int stateSlot, PhpNode key, PhpNode value) {
        this(generatorSlot, stateSlot, key, value, false);
    }

    public YieldNode(int generatorSlot, int stateSlot, PhpNode key, PhpNode value, boolean byRef) {
        this.generatorSlot = generatorSlot;
        this.stateSlot = stateSlot;
        this.key = key;
        this.value = value;
        this.byRef = byRef;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpGenerator generator = (PhpGenerator) frame.getObject(generatorSlot);
        if (frame.getInt(stateSlot) == 1) {
            frame.setInt(stateSlot, 0);
            return generator.takeInput();
        }
        Object yieldedKey = key == null ? null : key.execute(frame);
        Object yieldedValue = yieldValue(frame);
        generator.checkYield(this);
        yieldedKey = key == null ? generator.autoKey() : generator.explicitKey(yieldedKey);
        frame.setInt(stateSlot, 1);
        throw new ControlFlow.Yield(yieldedKey, yieldedValue);
    }

    private Object yieldValue(VirtualFrame frame) {
        if (!byRef) {
            return value == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(value.execute(frame));
        }
        if (value == null) {
            return new PhpReference(PhpNull.INSTANCE);
        }
        Object raw = value instanceof LValueNode lv
                ? lv.obtainCell(frame, lv.evalKeys(frame))
                : value.executeForRef(frame);
        if (raw instanceof PhpReference ref) {
            return ref;
        }
        PhpContext.get(this).warning("Only variable references should be yielded by reference", line());
        return new PhpReference(PhpOps.copyOnAssign(raw));
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginGenTakeInput();
        b.beginYield();
        int keyMode = key == null ? 0 : 1;
        if (byRef) {
            b.beginGenSuspendRef(keyMode, value != null);
            b.emitLoadLocal(e.generatorLocal());
            if (key != null) {
                e.expr(key);
            } else {
                b.emitLoadConstant(PhpNull.INSTANCE);
            }
            if (value instanceof LValueNode lv) {
                e.cell(lv);
            } else if (value != null) {
                e.exprForRef(value);
            } else {
                b.emitLoadConstant(PhpNull.INSTANCE);
            }
            b.endGenSuspendRef();
        } else {
            b.beginGenSuspend(keyMode);
            b.emitLoadLocal(e.generatorLocal());
            if (key != null) {
                e.expr(key);
            } else {
                b.emitLoadConstant(PhpNull.INSTANCE);
            }
            if (value != null) {
                e.expr(value);
            } else {
                b.emitLoadConstant(PhpNull.INSTANCE);
            }
            b.endGenSuspend();
        }
        b.endYield();
        b.endGenTakeInput();
    }
}
