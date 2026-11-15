package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;

/** Reads a constant from the per-context constant table at runtime. */
public final class ReadConstantNode extends PhpNode {

    private final String name;

    public ReadConstantNode(String name) {
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = PhpContext.get(this).readConstant(name);
        if (value == null) {
            throw new PhpError("Undefined constant \"" + name + "\"", this);
        }
        return value;
    }
}
