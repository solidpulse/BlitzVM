package dev.phpruntime.nodes;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
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

    public abstract Object execute(VirtualFrame frame);

    public void executeVoid(VirtualFrame frame) {
        execute(frame);
    }

    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return PhpTypesGen.expectLong(execute(frame));
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return PhpTypesGen.expectDouble(execute(frame));
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        return PhpTypesGen.expectBoolean(execute(frame));
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
