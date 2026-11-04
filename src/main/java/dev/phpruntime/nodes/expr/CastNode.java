package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

/** {@code (int)}, {@code (float)}, {@code (string)}, {@code (bool)}, {@code (array)}. */
public final class CastNode extends PhpNode {

    public enum Kind { INT, FLOAT, STRING, BOOL, ARRAY }

    @Child private PhpNode operand;
    private final Kind kind;

    public CastNode(Kind kind, PhpNode operand) {
        this.kind = kind;
        this.operand = operand;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = operand.execute(frame);
        return switch (kind) {
            case INT -> PhpOps.toLong(v);
            case FLOAT -> PhpOps.toDouble(v);
            case STRING -> PhpOps.toStr(v);
            case BOOL -> PhpOps.toBool(v);
            case ARRAY -> toArray(v);
        };
    }

    private static Object toArray(Object v) {
        if (v instanceof PhpArray) {
            return v;
        }
        PhpArray a = new PhpArray();
        if (v != PhpNull.INSTANCE) {
            a.append(v);
        }
        return a;
    }
}
