package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;

/**
 * Statement list containing {@code goto} labels. Split at label boundaries into segments
 * driven by a Truffle {@link LoopNode} so backward {@code goto} loops get OSR, safepoints,
 * and compile without repeated deoptimisation.
 */
public final class GotoDispatchNode extends PhpNode implements GotoEnterable {

    private final int pcSlot;
    private final int enterSlot;
    @Child private LoopNode loop;
    private final DispatchRepeatingNode repeating;
    @CompilationFinal(dimensions = 1) private final int[] exportedLabelIds;
    @CompilationFinal private int generatorActiveSlot = -1;

    public GotoDispatchNode(int pcSlot, int enterSlot, PhpNode[] segments,
                            int[] directLabelIds, int[] directLabelSegments,
                            int[] childLabelIds, int[] childLabelSegments) {
        this.pcSlot = pcSlot;
        this.enterSlot = enterSlot;
        this.repeating = new DispatchRepeatingNode(
                pcSlot, enterSlot, segments, directLabelIds, directLabelSegments, childLabelIds, childLabelSegments);
        this.loop = Truffle.getRuntime().createLoopNode(this.repeating);
        this.exportedLabelIds = new int[directLabelIds.length + childLabelIds.length];
        System.arraycopy(directLabelIds, 0, exportedLabelIds, 0, directLabelIds.length);
        System.arraycopy(childLabelIds, 0, exportedLabelIds, directLabelIds.length, childLabelIds.length);
    }

    @Override
    public int[] exportedLabelIds() {
        return exportedLabelIds;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (generatorActiveSlot < 0 || frame.getInt(generatorActiveSlot) == 0) {
            frame.setInt(pcSlot, 0);
            if (enterSlot >= 0) { frame.setInt(enterSlot, -1); }
            if (generatorActiveSlot >= 0) { frame.setInt(generatorActiveSlot, 1); }
        }
        try {
            loop.execute(frame);
        } catch (ControlFlow.Yield suspended) {
            throw suspended;
        } catch (RuntimeException | Error error) {
            if (generatorActiveSlot >= 0) { frame.setInt(generatorActiveSlot, 0); }
            throw error;
        }
        if (generatorActiveSlot >= 0) { frame.setInt(generatorActiveSlot, 0); }
        return null;
    }

    public GotoDispatchNode resumable(int activeSlot) {
        generatorActiveSlot = activeSlot;
        return this;
    }

    @Override
    public void executeEnter(VirtualFrame frame, int labelId) {
        if (generatorActiveSlot >= 0) { frame.setInt(generatorActiveSlot, 1); }
        int seg = repeating.findDirectSegment(labelId);
        if (seg >= 0) {
            frame.setInt(pcSlot, seg);
            if (enterSlot >= 0) {
                frame.setInt(enterSlot, -1);
            }
        } else {
            int childSeg = repeating.findChildSegment(labelId);
            frame.setInt(pcSlot, childSeg);
            frame.setInt(enterSlot, labelId);
        }
        try {
            loop.execute(frame);
        } catch (ControlFlow.Yield suspended) {
            throw suspended;
        } catch (RuntimeException | Error error) {
            if (generatorActiveSlot >= 0) { frame.setInt(generatorActiveSlot, 0); }
            throw error;
        }
        if (generatorActiveSlot >= 0) { frame.setInt(generatorActiveSlot, 0); }
    }

    private static final class DispatchRepeatingNode extends Node implements RepeatingNode {

        private final int pcSlot;
        private final int enterSlot;
        @Children private final PhpNode[] segments;
        @CompilationFinal(dimensions = 1) private final int[] directLabelIds;
        @CompilationFinal(dimensions = 1) private final int[] directLabelSegments;
        @CompilationFinal(dimensions = 1) private final int[] childLabelIds;
        @CompilationFinal(dimensions = 1) private final int[] childLabelSegments;

        DispatchRepeatingNode(int pcSlot, int enterSlot, PhpNode[] segments,
                              int[] directLabelIds, int[] directLabelSegments,
                              int[] childLabelIds, int[] childLabelSegments) {
            this.pcSlot = pcSlot;
            this.enterSlot = enterSlot;
            this.segments = segments;
            this.directLabelIds = directLabelIds;
            this.directLabelSegments = directLabelSegments;
            this.childLabelIds = childLabelIds;
            this.childLabelSegments = childLabelSegments;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            int pc = frame.getInt(pcSlot);
            int enterLabel = enterSlot >= 0 ? frame.getInt(enterSlot) : -1;
            if (enterLabel >= 0) {
                frame.setInt(enterSlot, -1);
            }
            try {
                executeSegment(frame, pc, enterLabel);
                int nextPc = pc + 1;
                if (nextPc >= segments.length) {
                    return false;
                }
                frame.setInt(pcSlot, nextPc);
                return true;
            } catch (ControlFlow.Goto g) {
                int seg = findDirectSegment(g.labelId);
                if (seg >= 0) {
                    frame.setInt(pcSlot, seg);
                    return true;
                }
                int childSeg = findChildSegment(g.labelId);
                if (childSeg >= 0) {
                    frame.setInt(pcSlot, childSeg);
                    frame.setInt(enterSlot, g.labelId);
                    return true;
                }
                throw g;
            }
        }

        @ExplodeLoop
        private void executeSegment(VirtualFrame frame, int pc, int enterLabel) {
            for (int i = 0; i < segments.length; i++) {
                if (i == pc) {
                    if (enterLabel >= 0 && segments[i] instanceof GotoEnterable enterable) {
                        enterable.executeEnter(frame, enterLabel);
                    } else {
                        segments[i].executeVoid(frame);
                    }
                    return;
                }
            }
        }

        @ExplodeLoop
        int findDirectSegment(int labelId) {
            for (int i = 0; i < directLabelIds.length; i++) {
                if (directLabelIds[i] == labelId) {
                    return directLabelSegments[i];
                }
            }
            return -1;
        }

        @ExplodeLoop
        int findChildSegment(int labelId) {
            for (int i = 0; i < childLabelIds.length; i++) {
                if (childLabelIds[i] == labelId) {
                    return childLabelSegments[i];
                }
            }
            return -1;
        }
    }
}
