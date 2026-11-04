package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;

/** A literal: int, float, string, bool or null. */
public final class ConstNode extends PhpNode {

    private final Object value;

    public ConstNode(Object value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    public Object value() {
        return value;
    }
}
