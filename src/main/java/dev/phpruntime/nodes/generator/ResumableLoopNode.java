package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;

/** init / condition / body / update phases; the LoopNode still supplies OSR and safepoints. */
public final class ResumableLoopNode extends PhpNode {
    @Child private PhpNode init;
    @Child private LoopNode loop;
    private final int stateSlot;
    private final boolean after;

    public ResumableLoopNode(PhpNode init, PhpNode condition, PhpNode body, PhpNode update, boolean after, int slot) {
        this.init = init;
        this.stateSlot = slot;
        this.after = after;
        this.loop = Truffle.getRuntime().createLoopNode(new Body(condition, body, update, after, slot));
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (frame.getInt(stateSlot) == 0) {
            if (init != null) {
                init.executeVoid(frame);
            }
            frame.setInt(stateSlot, after ? 2 : 1);
        }
        try {
            loop.execute(frame);
        } catch (ControlFlow.Yield suspend) {
            throw suspend;
        } catch (RuntimeException | Error error) {
            frame.setInt(stateSlot, 0);
            throw error;
        }
        frame.setInt(stateSlot, 0);
        return null;
    }

    private static final class Body extends Node implements RepeatingNode {
        @Child private PhpNode condition;
        @Child private PhpNode body;
        @Child private PhpNode update;
        private final boolean after;
        private final int slot;

        Body(PhpNode condition, PhpNode body, PhpNode update, boolean after, int slot) {
            this.condition = condition;
            this.body = body;
            this.update = update;
            this.after = after;
            this.slot = slot;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            int phase = frame.getInt(slot);
            if (phase == 1) {
                if (condition != null && !dev.phpruntime.nodes.expr.CondNode.toBool(condition.execute(frame))) {
                    return false;
                }
                frame.setInt(slot, 2);
                phase = 2;
            }
            if (phase == 2) {
                try {
                    body.executeVoid(frame);
                } catch (ControlFlow.Break stop) {
                    if (stop.levels > 1) {
                        throw new ControlFlow.Break(stop.levels - 1);
                    }
                    return false;
                } catch (ControlFlow.Continue next) {
                    if (next.levels > 1) {
                        throw new ControlFlow.Continue(next.levels - 1);
                    }
                }
                frame.setInt(slot, 3);
            }
            if (frame.getInt(slot) == 3 && update != null) {
                update.executeVoid(frame);
            }
            if (after) {
                frame.setInt(slot, 4);
                if (condition != null && !dev.phpruntime.nodes.expr.CondNode.toBool(condition.execute(frame))) {
                    return false;
                }
                frame.setInt(slot, 2);
            } else {
                frame.setInt(slot, 1);
            }
            return true;
        }
    }
}
