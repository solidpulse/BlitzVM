package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

/** A double-quoted string with embedded variables: "Hello $name". */
public final class InterpolateNode extends PhpNode {

    @Children private final PhpNode[] parts;

    public InterpolateNode(PhpNode[] parts) {
        this.parts = parts;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginInterpolate();
        for (PhpNode p : parts) {
            e.expr(p);
        }
        e.builder().endInterpolate();
    }
}
