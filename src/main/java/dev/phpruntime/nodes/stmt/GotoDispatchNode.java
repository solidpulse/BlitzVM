package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.phpruntime.nodes.PhpNode;

/**
 * Statement list containing {@code goto} labels, split at label boundaries into segments.
 * The bytecode emitter lowers the segments to a pc machine.
 */
public final class GotoDispatchNode extends PhpNode implements GotoEnterable {

    @CompilationFinal(dimensions = 1) private final PhpNode[] segments;
    @CompilationFinal(dimensions = 1) private final int[] directLabelIds;
    @CompilationFinal(dimensions = 1) private final int[] directLabelSegments;
    @CompilationFinal(dimensions = 1) private final int[] exportedLabelIds;

    public GotoDispatchNode(int pcSlot, int enterSlot, PhpNode[] segments,
                            int[] directLabelIds, int[] directLabelSegments,
                            int[] childLabelIds, int[] childLabelSegments) {
        this.segments = segments;
        this.directLabelIds = directLabelIds;
        this.directLabelSegments = directLabelSegments;
        this.exportedLabelIds = new int[directLabelIds.length + childLabelIds.length];
        System.arraycopy(directLabelIds, 0, exportedLabelIds, 0, directLabelIds.length);
        System.arraycopy(childLabelIds, 0, exportedLabelIds, directLabelIds.length, childLabelIds.length);
    }

    @Override
    public int[] exportedLabelIds() {
        return exportedLabelIds;
    }

    public PhpNode[] segments() {
        return segments;
    }

    public int[] directLabelIds() {
        return directLabelIds;
    }

    public int[] directLabelSegments() {
        return directLabelSegments;
    }

    /**
     * Bytecode has labels and branches: the segments stay in order and each direct label is a
     * branch target before its segment. Labels inside {@code if}/try segments are emitted by the
     * nested dispatch that owns them, sharing the emitter's label map with earlier {@code goto}s.
     */
    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.emitGotoMachine(this);
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emit(e);
    }
}
