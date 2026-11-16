package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

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

    // Both loops are exploded so each case node is a PE constant; a plain loop over @Children
    // made every case a virtual call taking the frame, which escaped and failed compilation.
    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object v = subject.execute(frame);
        int start = -1;
        int defaultIndex = -1;
        for (int i = 0; i < caseValues.length; i++) {
            if (caseValues[i] == null) {
                defaultIndex = i;
            } else if (PhpOps.looseEquals(v, caseValues[i].execute(frame))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            start = defaultIndex;
        }
        if (start < 0) {
            return null;
        }
        try {
            boolean running = false;
            for (int i = 0; i < caseBodies.length; i++) {
                running |= i == start;
                if (running) {
                    caseBodies[i].executeVoid(frame);
                }
            }
        } catch (ControlFlow.Break b) {
            if (b.levels > 1) {
                throw new ControlFlow.Break(b.levels - 1);
            }
        } catch (ControlFlow.Continue c) {
            // "continue" inside switch acts like "break" for the switch itself.
            if (c.levels > 1) {
                throw new ControlFlow.Continue(c.levels - 1);
            }
        }
        return null;
    }
}
