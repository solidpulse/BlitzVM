package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.js.runtime.JsOps;
import dev.phpruntime.js.runtime.JsTypes;
import dev.phpruntime.js.runtime.JsTypesGen;

/** Base of every JavaScript AST node. Statements return undefined. */
@TypeSystemReference(JsTypes.class)
@NodeInfo(language = "JavaScript")
public abstract class JsNode extends Node {

    private Source source;
    private int line;

    public abstract Object executeGeneric(VirtualFrame frame);

    public void executeVoid(VirtualFrame frame) {
        executeGeneric(frame);
    }

    /** Typed fast paths: a parent that expects a primitive avoids boxing; a mismatch rewrites the parent. */
    public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        return JsTypesGen.expectInteger(executeGeneric(frame));
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return JsTypesGen.expectDouble(executeGeneric(frame));
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        return JsTypesGen.expectBoolean(executeGeneric(frame));
    }

    /** JavaScript truthiness of this node's value, without boxing when it is already a boolean. */
    public final boolean executeCondition(VirtualFrame frame) {
        try {
            return executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            return JsOps.toBoolean(e.getResult());
        }
    }

    public final <T extends JsNode> T at(Source src, int ln) {
        this.source = src;
        this.line = ln;
        @SuppressWarnings("unchecked")
        T self = (T) this;
        return self;
    }

    @Override
    public SourceSection getSourceSection() {
        if (source == null || line <= 0 || line > source.getLineCount()) {
            return null;
        }
        return source.createSection(line);
    }
}
