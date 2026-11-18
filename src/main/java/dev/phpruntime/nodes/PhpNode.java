package dev.phpruntime.nodes;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Base of every syntax node. Nodes carry source lines for diagnostics and emit themselves
 * to bytecode; they have no execution semantics.
 */
@NodeInfo(language = "PHP")
public abstract class PhpNode extends Node {

    /** Source line for error messages; -1 if unknown. */
    private int line = -1;

    @Override
    public SourceSection getSourceSection() {
        RootNode root = getRootNode();
        if (line > 0 && root instanceof PhpRootNode r) {
            return section(r.source(), line);
        }
        return null;
    }

    static SourceSection section(Source source, int line) {
        if (source == null || line <= 0 || line > source.getLineCount()) {
            return null;
        }
        return source.createSection(line);
    }

    /**
     * Emits this node as bytecode that leaves its value on the stack. Nodes without a
     * bytecode form keep this default, which is an error.
     */
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        throw e.unsupported(this);
    }

    /** Emits this node where its value is not used; statements override this. */
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emit(e);
    }

    public final int line() {
        return line;
    }

    public final <T extends PhpNode> T at(int line) {
        this.line = line;
        @SuppressWarnings("unchecked")
        T self = (T) this;
        return self;
    }
}
