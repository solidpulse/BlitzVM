package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;

/**
 * switch with fall-through. {@code caseValues[i] == null} marks {@code default}.
 * Matching uses loose comparison, as in PHP.
 */
public final class SwitchNode extends PhpNode {

    @Child private PhpNode subject;
    @Children private final PhpNode[] caseValues;
    @Children private final PhpNode[] caseBodies;

    public SwitchNode(PhpNode subject, PhpNode[] caseValues, PhpNode[] caseBodies) {
        this.subject = subject;
        this.caseValues = caseValues;
        this.caseBodies = caseBodies;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginBlock();
        var subjectLocal = e.temp();
        b.beginStoreLocal(subjectLocal);
        e.expr(subject);
        b.endStoreLocal();
        var startLocal = e.temp();
        b.beginStoreLocal(startLocal);
        b.emitLoadConstant(-1L);
        b.endStoreLocal();
        com.oracle.truffle.api.bytecode.BytecodeLabel dispatch = b.createLabel();
        com.oracle.truffle.api.bytecode.BytecodeLabel end = b.createLabel();
        int defaultIndex = -1;
        for (int i = 0; i < caseValues.length; i++) {
            if (caseValues[i] == null) {
                defaultIndex = i;
                continue;
            }
            b.beginIfThen();
            b.beginEqual(false);
            b.emitLoadLocal(subjectLocal);
            e.expr(caseValues[i]);
            b.endEqual();
            b.beginBlock();
            b.beginStoreLocal(startLocal);
            b.emitLoadConstant((long) i);
            b.endStoreLocal();
            b.emitBranch(dispatch);
            b.endBlock();
            b.endIfThen();
        }
        if (defaultIndex >= 0) {
            b.beginStoreLocal(startLocal);
            b.emitLoadConstant((long) defaultIndex);
            b.endStoreLocal();
        }
        b.emitLabel(dispatch);
        com.oracle.truffle.api.bytecode.BytecodeLabel[] bodies = new com.oracle.truffle.api.bytecode.BytecodeLabel[caseBodies.length];
        for (int i = 0; i < bodies.length; i++) {
            bodies[i] = b.createLabel();
            b.beginIfThen();
            b.beginIdentical(false);
            b.emitLoadLocal(startLocal);
            b.emitLoadConstant((long) i);
            b.endIdentical();
            b.emitBranch(bodies[i]);
            b.endIfThen();
        }
        b.emitBranch(end);
        e.pushLoop(end, end);
        for (int i = 0; i < caseBodies.length; i++) {
            b.emitLabel(bodies[i]);
            b.beginBlock();
            e.stmt(caseBodies[i]);
            b.endBlock();
        }
        e.popLoop();
        b.emitLabel(end);
        b.endBlock();
    }
}
