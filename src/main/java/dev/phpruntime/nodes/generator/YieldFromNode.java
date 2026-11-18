package dev.phpruntime.nodes.generator;

import dev.phpruntime.nodes.PhpNode;

/** Delegation retains the source and its position; the bytecode emitter writes the loop. */
public final class YieldFromNode extends PhpNode {
    @Child private PhpNode source;

    public YieldFromNode(PhpNode source) {
        this.source = source;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        com.oracle.truffle.api.bytecode.BytecodeLocal gen = e.generatorLocal();
        com.oracle.truffle.api.bytecode.BytecodeLocal cursor = e.temp();
        b.beginBlock();
        b.beginStoreLocal(cursor);
        b.beginYieldFromStart();
        e.expr(source);
        b.endYieldFromStart();
        b.endStoreLocal();
        b.beginWhile();
        b.beginYieldFromValid();
        b.emitLoadLocal(cursor);
        b.endYieldFromValid();
        b.beginYieldFromResume();
        b.emitLoadLocal(gen);
        b.emitLoadLocal(cursor);
        b.beginYield();
        b.beginYieldFromSuspend();
        b.emitLoadLocal(gen);
        b.emitLoadLocal(cursor);
        b.endYieldFromSuspend();
        b.endYield();
        b.endYieldFromResume();
        b.endWhile();
        b.beginYieldFromResult();
        b.emitLoadLocal(cursor);
        b.endYieldFromResult();
        b.endBlock();
    }
}
