package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.bytecode.BytecodeLocal;
import dev.phpruntime.nodes.PhpNode;

/**
 * {@code @expr}: php lowers error_reporting to the fatal levels while the operand runs and
 * restores it afterwards, including when the operand throws. The saved level lives in a local
 * and the restore is the finally of a TryFinally, so an exception out of the operand still
 * restores it.
 */
public final class SilenceNode extends PhpNode {

    @Child private PhpNode operand;

    public SilenceNode(PhpNode operand) {
        this.operand = operand;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        BytecodeLocal previous = e.temp();
        BytecodeLocal result = e.temp();
        b.beginBlock();
        b.beginStoreLocal(previous);
        b.emitSilenceBegin();
        b.endStoreLocal();
        b.beginTryFinally(() -> {
            b.beginSilenceEnd();
            b.emitLoadLocal(previous);
            b.endSilenceEnd();
        });
        b.beginBlock();
        b.beginStoreLocal(result);
        e.expr(operand);
        b.endStoreLocal();
        b.endBlock();
        b.endTryFinally();
        b.emitLoadLocal(result);
        b.endBlock();
    }
}
