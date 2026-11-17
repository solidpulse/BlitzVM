package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;

/** Conditional {@code function name(...) { ... }} declaration executed when reached. */
public final class DeclareFunctionNode extends PhpNode {

    private final PhpFunction function;

    public DeclareFunctionNode(PhpFunction function) {
        this.function = function;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        try {
            PhpContext.get(this).declareFunction(function);
        } catch (PhpError e) {
            throw e.at(this);
        }
        return null;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().emitDeclareFunction(function);
    }
}
