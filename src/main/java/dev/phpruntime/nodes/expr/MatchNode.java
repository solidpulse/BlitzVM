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
        String type = v instanceof PhpObject obj ? obj.phpClass().name() : PhpOps.typeName(v);
        throw new PhpError("UnhandledMatchError", "Unhandled match case of type " + type, this);
    }
}
