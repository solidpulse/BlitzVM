package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/**
 * PHP 8.5's pipe operator: {@code $value |> $callable}. The left operand is evaluated first
 * and then passed as the only argument to the callable the right operand evaluates to.
 */
public final class PipeNode extends PhpNode {

    @Child private PhpNode value;
    @Child private PhpNode callable;
    private final int outerThisSlot;

    public PipeNode(PhpNode value, PhpNode callable, int outerThisSlot) {
        this.value = value;
        this.callable = callable;
        this.outerThisSlot = outerThisSlot;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        boolean keepRef = e.takeRef(this);
        com.oracle.truffle.api.bytecode.BytecodeLocal piped = e.temp();
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        try {
            b.beginBlock();
            b.beginStoreLocal(piped);
            e.expr(value);
            b.endStoreLocal();
            b.beginResolvePipeCallable(callee);
            e.expr(callable);
            e.loadSlotOrNull(outerThisSlot);
            b.endResolvePipeCallable();
            b.beginDoValueCall(keepRef);
            b.emitLoadLocal(callee);
            b.beginSendValue(0, 0, callee);
            b.beginPipeArgument(callee);
            b.emitLoadLocal(piped);
            b.endPipeArgument();
            b.endSendValue();
            b.endDoValueCall();
            b.endBlock();
        } finally {
            e.exitCall();
        }
    }
}
