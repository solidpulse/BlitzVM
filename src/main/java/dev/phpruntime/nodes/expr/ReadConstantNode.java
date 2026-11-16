package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpConstants;
import dev.phpruntime.runtime.PhpError;

/** Reads a constant from the per-context constant table at runtime. */
public final class ReadConstantNode extends PhpNode {

    private final String name;
    private final String nsName;

    public ReadConstantNode(String name) {
        this(name, null);
    }

    public ReadConstantNode(String name, String nsName) {
        this.name = name;
        this.nsName = nsName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        if (nsName != null) {
            Object nsValue = ctx.readConstant(nsName);
            if (nsValue != null) {
                return nsValue;
            }
            Object compileTime = PhpConstants.compileTime(name);
            if (compileTime != null) {
                return compileTime;
            }
        }
        Object value = ctx.readConstant(name);
        if (value == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Undefined constant \"" + (nsName != null ? nsName : name) + "\"", this);
        }
        return value;
    }
}
