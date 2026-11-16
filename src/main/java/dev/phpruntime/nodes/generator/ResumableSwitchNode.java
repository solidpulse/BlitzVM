package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

public final class ResumableSwitchNode extends PhpNode {
    @Child private PhpNode subject;
    @Children private final PhpNode[] values;
    @Children private final PhpNode[] bodies;
    private final int slot;
    private final int valueSlot;

    public ResumableSwitchNode(PhpNode subject, PhpNode[] values, PhpNode[] bodies, int slot, int valueSlot) {
        this.subject = subject;
        this.values = values;
        this.bodies = bodies;
        this.slot = slot;
        this.valueSlot = valueSlot;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        int start = frame.getInt(slot) - 1;
        if (start < 0) {
            Object value = subject.execute(frame);
            frame.setObject(valueSlot, value);
            int fallback = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) { fallback = i; }
                else if (PhpOps.looseEquals(value, values[i].execute(frame))) { start = i; break; }
            }
            if (start < 0) { start = fallback; }
        }
        try {
            for (int i = 0; i < bodies.length; i++) {
                if (start >= 0 && i >= start) {
                    frame.setInt(slot, i + 1);
                    bodies[i].executeVoid(frame);
                }
            }
        } catch (ControlFlow.Break stop) {
            if (stop.levels > 1) { throw new ControlFlow.Break(stop.levels - 1); }
        } catch (ControlFlow.Continue next) {
            if (next.levels > 1) { throw new ControlFlow.Continue(next.levels - 1); }
        }
        frame.setInt(slot, 0);
        frame.setObject(valueSlot, null);
        return null;
    }
}
