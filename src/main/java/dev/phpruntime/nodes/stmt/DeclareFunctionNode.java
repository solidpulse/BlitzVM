package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpFunction;

/** Conditional {@code function name(...) { ... }} declaration executed when reached. */
public final class DeclareFunctionNode extends PhpNode {

    private final PhpFunction function;

    public DeclareFunctionNode(PhpFunction function) {
        this.function = function;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().emitDeclareFunction(function);
    }
}
