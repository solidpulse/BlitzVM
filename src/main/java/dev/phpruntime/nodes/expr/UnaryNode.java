package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** Unary {@code +} (numeric conversion) and {@code ~} (bitwise not). */
public final class UnaryNode extends PhpNode {

    @Child private PhpNode operand;
    private final boolean bitNot;

    public UnaryNode(PhpNode operand, boolean bitNot) {
        this.operand = operand;
        this.bitNot = bitNot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = operand.execute(frame);
        try {
            return bitNot ? ~PhpOps.toLong(v) : PhpOps.toNumber(v);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }
}
