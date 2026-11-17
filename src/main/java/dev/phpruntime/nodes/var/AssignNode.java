package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/**
 * {@code target = value}. Arrays are shared copy-on-write, giving PHP value semantics.
 *
 * <p>Remembers what the value has been: always an int, always a float, a mix of the
 * two, or anything. For numbers it executes the value typed and writes it unboxed, so
 * {@code $zr = $t} in a loop does not allocate. The kinds only widen.
 */
public final class AssignNode extends PhpNode {

    private static final int UNKNOWN = 0;
    private static final int LONG = 1;
    private static final int DOUBLE = 2;
    private static final int NUMBER = 3;
    private static final int OBJECT = 4;

    @Child private LValueNode target;
    @Child private PhpNode value;
    @CompilationFinal private int kind = UNKNOWN;

    public AssignNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] keys = target.evalKeys(frame);
        switch (kind) {
            case LONG:
                try {
                    long l = value.executeLong(frame);
                    target.writeLong(frame, keys, l);
                    return l;
                } catch (UnexpectedResultException e) {
                    return e.getResult() instanceof Long l ? store(frame, keys, l) : widen(frame, keys, e.getResult());
                }
            case DOUBLE:
                try {
                    double d = value.executeDouble(frame);
                    target.writeDouble(frame, keys, d);
                    return d;
                } catch (UnexpectedResultException e) {
                    return e.getResult() instanceof Double d ? store(frame, keys, d) : widen(frame, keys, e.getResult());
                }
            case NUMBER: {
                // Branch on the type rather than catch an UnexpectedResultException: the
                // DSL's typed execute methods deoptimize before throwing one, and a value
                // that keeps alternating between int and float would deoptimize forever.
                Object v = value.execute(frame);
                if (v instanceof Long l) {
                    target.writeLong(frame, keys, l);
                    return l;
                }
                if (v instanceof Double d) {
                    target.writeDouble(frame, keys, d);
                    return d;
                }
                return widen(frame, keys, v);
            }
            case OBJECT:
                return generic(frame, keys, value.execute(frame));
            default:
                return widen(frame, keys, value.execute(frame));
        }
    }

    private Object widen(VirtualFrame frame, Object[] keys, Object v) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        int seen = v instanceof Long ? LONG : v instanceof Double ? DOUBLE : OBJECT;
        if (kind == UNKNOWN || kind == seen) {
            kind = seen;
        } else if (seen != OBJECT && kind != OBJECT) {
            kind = NUMBER;
        } else {
            kind = OBJECT;
        }
        return generic(frame, keys, v);
    }

    /** A result of the expected type that came back boxed: no reason to deoptimize. */
    private Object store(VirtualFrame frame, Object[] keys, Object v) {
        target.write(frame, keys, v);
        return v;
    }

    private Object generic(VirtualFrame frame, Object[] keys, Object v) {
        target.write(frame, keys, PhpOps.copyOnAssign(v));
        return v;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("assign to " + target.getClass().getSimpleName());
        }
        e.builder().beginWriteVar(e.accessor(lv), e.info(lv));
        e.expr(value);
        e.builder().endWriteVar();
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("assign to " + target.getClass().getSimpleName());
        }
        e.builder().beginStoreVar(e.accessor(lv), e.info(lv));
        e.expr(value);
        e.builder().endStoreVar();
    }
}
