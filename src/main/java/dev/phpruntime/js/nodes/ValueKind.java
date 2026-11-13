package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Profiles what kind of value an expression produces where the result has to travel as
 * an Object (a return value, the value of {@code ?:}). Once a site is known to produce
 * ints, or numbers that are sometimes doubles, it executes the expression typed and
 * boxes one type only. Graal removes a box it can see all uses of, but not two
 * different box types meeting in one value, which is what an int-or-double result
 * would otherwise be.
 *
 * <p>Kinds only widen: INT to DOUBLE when a double shows up (ints are then returned as
 * doubles, which JavaScript cannot tell apart), anything to OBJECT for a non-number.
 */
final class ValueKind {

    private static final int UNKNOWN = 0;
    private static final int INT = 1;
    private static final int DOUBLE = 2;
    private static final int OBJECT = 3;

    @CompilationFinal private int kind = UNKNOWN;

    Object execute(VirtualFrame frame, JsNode node) {
        switch (kind) {
            case INT:
                try {
                    return node.executeInt(frame);
                } catch (UnexpectedResultException e) {
                    return widen(e.getResult());
                }
            case DOUBLE:
                try {
                    return node.executeDouble(frame);
                } catch (UnexpectedResultException e) {
                    return widen(e.getResult());
                }
            case OBJECT:
                return node.executeGeneric(frame);
            default:
                return widen(node.executeGeneric(frame));
        }
    }

    private Object widen(Object v) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        if (kind == OBJECT || !(v instanceof Integer || v instanceof Double)) {
            kind = OBJECT;
        } else if (v instanceof Double || kind == DOUBLE) {
            kind = DOUBLE;
        } else {
            kind = INT;
        }
        return kind == DOUBLE && v instanceof Integer i ? (Object) (double) i : v;
    }
}
