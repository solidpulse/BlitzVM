package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** PHP 8 {@code match ($subject) { ... }} expression with strict comparison and no fall-through. */
public final class MatchNode extends PhpNode {

    public static final class MatchArm extends Node {
        @Children private final PhpNode[] conditions;
        @Child private PhpNode body;

        public MatchArm(PhpNode[] conditions, PhpNode body) {
            this.conditions = conditions;
            this.body = body;
        }

        @ExplodeLoop
        boolean matches(VirtualFrame frame, Object subjectValue) {
            for (PhpNode cond : conditions) {
                if (PhpOps.strictEquals(subjectValue, cond.execute(frame))) {
                    return true;
                }
            }
            return false;
        }

        Object executeBody(VirtualFrame frame) {
            return body.execute(frame);
        }
    }

    @Child private PhpNode subject;
    @Children private final MatchArm[] arms;
    @Child private PhpNode defaultBody;

    public MatchNode(PhpNode subject, MatchArm[] arms, PhpNode defaultBody) {
        this.subject = subject;
        this.arms = arms;
        this.defaultBody = defaultBody;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object v = subject.execute(frame);
        for (MatchArm arm : arms) {
            if (arm.matches(frame, v)) {
                return arm.executeBody(frame);
            }
        }
        if (defaultBody != null) {
            return defaultBody.execute(frame);
        }
        CompilerDirectives.transferToInterpreter();
        throw unhandled(v, this);
    }

    /** php's {@code UnhandledMatchError} for a subject; null {@code location} lets the root locate it. */
    public static PhpError unhandled(Object v, Node location) {
        String type = v instanceof PhpObject obj ? obj.phpClass().name() : PhpOps.typeName(v);
        String message = "Unhandled match case of type " + type;
        return new PhpError("UnhandledMatchError", message, location);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal subjectLocal = e.temp();
        b.beginStoreLocal(subjectLocal);
        e.expr(subject);
        b.endStoreLocal();
        com.oracle.truffle.api.bytecode.BytecodeLocal result = e.temp();
        com.oracle.truffle.api.bytecode.BytecodeLabel end = b.createLabel();
        for (MatchArm arm : arms) {
            for (PhpNode condition : arm.conditions) {
                b.beginIfThen();
                b.beginIdentical(false);
                b.emitLoadLocal(subjectLocal);
                e.expr(condition);
                b.endIdentical();
                b.beginBlock();
                b.beginStoreLocal(result);
                e.expr(arm.body);
                b.endStoreLocal();
                b.emitBranch(end);
                b.endBlock();
                b.endIfThen();
            }
        }
        b.beginStoreLocal(result);
        if (defaultBody != null) {
            e.expr(defaultBody);
        } else {
            b.beginUnhandledMatch();
            b.emitLoadLocal(subjectLocal);
            b.endUnhandledMatch();
        }
        b.endStoreLocal();
        b.emitLabel(end);
        b.emitLoadLocal(result);
        b.endBlock();
    }
}
