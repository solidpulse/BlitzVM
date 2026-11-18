package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.nodes.Node;
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

    /** The conversion, shared with the bytecode {@code Unary} operation. */
    public static Object apply(Object v, boolean bitNot, Node location) {
        try {
            if (bitNot && v instanceof String s) {
                return invertBytes(s);
            }
            return bitNot ? ~PhpOps.toLong(v) : PhpOps.toNumber(v);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(location);
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginUnary(bitNot);
        e.expr(operand);
        e.builder().endUnary();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String invertBytes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            out.append((char) (s.charAt(i) ^ 255));
        }
        return out.toString();
    }
}
