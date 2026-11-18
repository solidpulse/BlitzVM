package dev.phpruntime.nodes.generator;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpNull;

/** One yield site. The bytecode emitter writes the suspend instruction. */
public final class YieldNode extends PhpNode {
    @Child private PhpNode key;
    @Child private PhpNode value;
    /** The enclosing function is declared {@code function &f()}: yield the value's cell. */
    private final boolean byRef;

    public YieldNode(PhpNode key, PhpNode value, boolean byRef) {
        this.key = key;
        this.value = value;
        this.byRef = byRef;
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
