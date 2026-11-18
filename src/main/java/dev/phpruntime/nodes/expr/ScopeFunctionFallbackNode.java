package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;

/** Unqualified calls inside namespaces must still prefer a user function over the global builtin. */
public final class ScopeFunctionFallbackNode extends PhpNode {
    private final int functionId;
    @Child private PhpNode userCall;
    @Child private PhpNode builtin;
    public ScopeFunctionFallbackNode(int functionId, PhpNode userCall, PhpNode builtin) {
        this.functionId = functionId;
        this.userCall = userCall;
        this.builtin = builtin;
    }
    @Override public Object execute(VirtualFrame frame) {
        return PhpContext.get(this).functionById(functionId) != null ? userCall.execute(frame) : builtin.execute(frame);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginConditional();
        b.emitFunctionDefined(functionId);
        e.expr(userCall);
        e.expr(builtin);
        b.endConditional();
    }
}
