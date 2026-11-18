package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;

/** {@code foreach ($arr as $k => [$v | &$v | [...] ])}. */
public final class ForeachNode extends PhpNode {

    @Child private PhpNode subject;
    @Child private LValueNode subjectLValue;
    @Child private LValueNode keyTarget;
    @Child private LValueNode valueTarget;
    private final boolean byRef;
    @Child private PhpNode body;

    public ForeachNode(PhpNode subject, LValueNode keyTarget, LValueNode valueTarget, PhpNode body) {
        this(subject, null, keyTarget, valueTarget, false, body);
    }

    public ForeachNode(PhpNode subject, LValueNode subjectLValue, LValueNode keyTarget, LValueNode valueTarget,
                        boolean byRef, PhpNode body) {
        this.subject = subject;
        this.subjectLValue = subjectLValue;
        this.keyTarget = keyTarget;
        this.valueTarget = valueTarget;
        this.byRef = byRef;
        this.body = body;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (byRef) {
            emitByRef(e);
            return;
        }
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal cursor = e.temp();
        b.beginStoreLocal(cursor);
        b.beginForeachStart();
        e.expr(subject);
        b.endForeachStart();
        b.endStoreLocal();
        b.beginTryFinally(() -> {
            b.beginForeachEnd();
            b.emitLoadLocal(cursor);
            b.endForeachEnd();
        });
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel exit = b.createLabel();
        b.beginWhile();
        b.beginForeachNext();
        b.emitLoadLocal(cursor);
        b.endForeachNext();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel next = b.createLabel();
        if (keyTarget != null) {
            // php (and executeIterator) fetches current() before key(), then assigns the key
            // and the value in that order.
            com.oracle.truffle.api.bytecode.BytecodeLocal value = e.temp();
            b.beginStoreLocal(value);
            b.beginForeachValue();
            b.emitLoadLocal(cursor);
            b.endForeachValue();
            b.endStoreLocal();
            e.assign(keyTarget, () -> {
                b.beginForeachKey();
                b.emitLoadLocal(cursor);
                b.endForeachKey();
            });
            e.assign(valueTarget, () -> b.emitLoadLocal(value));
        } else {
            e.assign(valueTarget, () -> {
                b.beginForeachValue();
                b.emitLoadLocal(cursor);
                b.endForeachValue();
            });
        }
        e.pushLoop(exit, next);
        e.stmt(body);
        e.popLoop();
        b.emitLabel(next);
        b.endBlock();
        b.endWhile();
        b.emitLabel(exit);
        b.endBlock();
        b.endTryFinally();
        b.endBlock();
    }

    private void emitByRef(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal cursor = e.temp();
        b.beginStoreLocal(cursor);
        if (subjectLValue != null) {
            e.refStart(subjectLValue, subjectLValue.describe());
        } else {
            b.beginForeachRefStartValue(e.callerConstant());
            e.exprForRef(subject);
            e.traitSelfOperand();
            b.endForeachRefStartValue();
        }
        b.endStoreLocal();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel exit = b.createLabel();
        b.beginWhile();
        b.beginForeachRefNext();
        b.emitLoadLocal(cursor);
        b.endForeachRefNext();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLabel next = b.createLabel();
        if (keyTarget != null) {
            e.assign(keyTarget, () -> {
                b.beginForeachRefKey();
                b.emitLoadLocal(cursor);
                b.endForeachRefKey();
            });
        }
        e.bind(valueTarget, () -> {
            b.beginForeachRefCell();
            b.emitLoadLocal(cursor);
            b.endForeachRefCell();
        });
        e.pushLoop(exit, next);
        e.stmt(body);
        e.popLoop();
        b.emitLabel(next);
        b.endBlock();
        b.endWhile();
        b.emitLabel(exit);
        b.endBlock();
        b.endBlock();
    }
}
