package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;

/**
 * {@code global $a;} at file scope: the variable already is the global, but php creates it
 * (as null) if it does not exist yet, so later reads do not warn.
 */
public final class EnsureVariablesNode extends PhpNode {

    @Children private final LValueNode[] targets;

    public EnsureVariablesNode(LValueNode[] targets) {
        this.targets = targets;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginBlock();
        for (LValueNode t : targets) {
            if (!(t instanceof dev.phpruntime.nodes.var.GlobalVarNode)) {
                throw e.unsupported("ensure " + t.getClass().getSimpleName());
            }
            e.place(t, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_CELL, 0, null);
        }
        e.builder().endBlock();
    }
}
