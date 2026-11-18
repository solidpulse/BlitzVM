package dev.phpruntime.nodes;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.runtime.PhpTypes;
import dev.phpruntime.runtime.PhpTypesGen;

/**
 * Base of every AST node. Expressions return their value from {@link #execute};
 * statements return null. Typed execute methods let parents that expect a primitive
 * avoid boxing; a mismatch triggers node rewriting via UnexpectedResultException.
 */
@TypeSystemReference(PhpTypes.class)
@NodeInfo(language = "PHP")
public abstract class PhpNode extends Node {

    /** Source line for error messages; -1 if unknown. */
    private int line = -1;

    public Object execute(VirtualFrame frame) {
        throw new IllegalStateException("AST execution removed: " + getClass().getSimpleName());
    }

    /** Executes this expression preserving a {@code PhpReference} cell when returned by reference. */
    public Object executeForRef(VirtualFrame frame) {
        return execute(frame);
    }

    public void executeVoid(VirtualFrame frame) {
        execute(frame);
    }

    /*
     * Typed execution for nodes without a DSL specialization. These check the type
     * directly instead of using PhpTypesGen.expectX, which deoptimizes before throwing.
     * A caller that sees the exception changes its own state, so the deopt would be
     * wasted, and with a value that alternates between types it would repeat forever.
     */
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object v = execute(frame);
        if (v instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(v);
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object v = execute(frame);
        if (v instanceof Double d) {
            return d;
        }
        throw new UnexpectedResultException(v);
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        return PhpTypesGen.expectBoolean(execute(frame));
    }

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
     * bytecode form keep this default, and their function stays on the AST.
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
