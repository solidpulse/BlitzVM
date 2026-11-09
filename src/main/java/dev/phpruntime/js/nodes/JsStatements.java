package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import dev.phpruntime.js.runtime.JsOps;
import dev.phpruntime.js.runtime.JsValues.Undefined;

/** Statements. */
public final class JsStatements {

    private JsStatements() {
    }

    public static final class Block extends JsNode {
        @Children private final JsNode[] statements;

        public Block(JsNode[] statements) {
            this.statements = statements;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            for (JsNode s : statements) {
                s.executeVoid(frame);
            }
            return Undefined.INSTANCE;
        }
    }

    public static final class If extends JsNode {
        @Child private JsNode condition;
        @Child private JsNode then;
        @Child private JsNode otherwise;
        private final CountingConditionProfile profile = CountingConditionProfile.create();

        public If(JsNode condition, JsNode then, JsNode otherwise) {
            this.condition = condition;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            if (profile.profile(condition.executeCondition(frame))) {
                then.executeVoid(frame);
            } else if (otherwise != null) {
                otherwise.executeVoid(frame);
            }
            return Undefined.INSTANCE;
        }
    }

    /** while and for loops. The LoopNode polls safepoints on every back-edge. */
    public static final class Loop extends JsNode {
        @Child private JsNode init;
        @Child private LoopNode loop;

        public Loop(JsNode init, JsNode condition, JsNode update, JsNode body) {
            this.init = init;
            this.loop = Truffle.getRuntime().createLoopNode(new Body(condition, update, body));
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            if (init != null) {
                init.executeVoid(frame);
            }
            loop.execute(frame);
            return Undefined.INSTANCE;
        }

        private static final class Body extends Node implements RepeatingNode {
            @Child private JsNode condition;
            @Child private JsNode update;
            @Child private JsNode body;

            Body(JsNode condition, JsNode update, JsNode body) {
                this.condition = condition;
                this.update = update;
                this.body = body;
            }

            @Override
            public boolean executeRepeating(VirtualFrame frame) {
                if (condition != null && !condition.executeCondition(frame)) {
                    return false;
                }
                try {
                    body.executeVoid(frame);
                } catch (JsControl.Break b) {
                    return false;
                } catch (JsControl.Continue c) {
                    // fall through to the update
                }
                if (update != null) {
                    update.executeVoid(frame);
                }
                return true;
            }
        }
    }

    public static final class Return extends JsNode {
        @Child private JsNode value;

        public Return(JsNode value) {
            this.value = value;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            throw new JsControl.Return(value == null ? Undefined.INSTANCE : value.executeGeneric(frame));
        }
    }

    public static final class Break extends JsNode {
        @Override
        public Object executeGeneric(VirtualFrame frame) {
            throw JsControl.Break.INSTANCE;
        }
    }

    public static final class Continue extends JsNode {
        @Override
        public Object executeGeneric(VirtualFrame frame) {
            throw JsControl.Continue.INSTANCE;
        }
    }

    public static final class Empty extends JsNode {
        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return Undefined.INSTANCE;
        }
    }
}
