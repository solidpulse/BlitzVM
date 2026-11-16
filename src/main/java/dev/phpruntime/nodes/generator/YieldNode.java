package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpGenerator;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

/** One yield site. Its state and intermediate operands are in hidden per-invocation slots. */
public final class YieldNode extends PhpNode {
    @Child private PhpNode key;
    @Child private PhpNode value;
    private final int stateSlot;
    private final int generatorSlot;

    public YieldNode(int generatorSlot, int stateSlot, PhpNode key, PhpNode value) {
        this.generatorSlot = generatorSlot;
        this.stateSlot = stateSlot;
        this.key = key;
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpGenerator generator = (PhpGenerator) frame.getObject(generatorSlot);
        if (frame.getInt(stateSlot) == 1) {
            frame.setInt(stateSlot, 0);
            return generator.takeInput();
        }
        Object yieldedKey = key == null ? null : key.execute(frame);
        Object yieldedValue = value == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(value.execute(frame));
        yieldedKey = key == null ? generator.autoKey() : generator.explicitKey(yieldedKey);
        frame.setInt(stateSlot, 1);
        throw new ControlFlow.Yield(yieldedKey, yieldedValue);
    }
}
