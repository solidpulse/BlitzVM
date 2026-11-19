package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;

/** {@code [1, 'k' => 2, &$x, ...$other]} or {@code array(...)}. A null key node means "next index". */
public final class ArrayLiteralNode extends PhpNode {

    @Children private final PhpNode[] keys;
    @Children private final PhpNode[] values;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;
    @CompilationFinal(dimensions = 1) private final boolean[] spread;
    /**
     * The value when every key and value is a literal: built once here and emitted as a
     * constant, in every request, as an immutable array (writers copy it first).
     */
    private final PhpArray constant;

    public ArrayLiteralNode(PhpNode[] keys, PhpNode[] values) {
        this(keys, values, new boolean[values.length], new boolean[values.length]);
    }

    public ArrayLiteralNode(PhpNode[] keys, PhpNode[] values, boolean[] byRef, boolean[] spread) {
        this.keys = keys;
        this.values = values;
        this.byRef = byRef;
        this.spread = spread;
        this.constant = buildConstant(keys, values, byRef, spread);
    }

    /** The immutable value of this literal when every element is a literal, else null. */
    public PhpArray constant() {
        return constant;
    }

    /** The immutable value of an all-literal array, or null if anything must be emitted. */
    private static PhpArray buildConstant(PhpNode[] keys, PhpNode[] values, boolean[] byRef, boolean[] spread) {
        PhpArray a = new PhpArray();
        for (int i = 0; i < values.length; i++) {
            if (byRef[i] || spread[i]) {
                return null;
            }
            Object v;
            if (values[i] instanceof ConstNode c && isScalar(c.value())) {
                v = c.value();
            } else if (values[i] instanceof ArrayLiteralNode inner && inner.constant != null) {
                v = inner.constant;
            } else {
                return null;
            }
            if (keys[i] == null) {
                try {
                    a.append(v);
                } catch (PhpError e) {
                    return null;
                }
            } else if (keys[i] instanceof ConstNode c && isScalar(c.value()) && !(c.value() instanceof Double)) {
                // Float keys are left out: php reports a lossy conversion on each execution.
                try {
                    a.put(c.value(), v);
                } catch (PhpError e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return a.freeze();
    }

    private static boolean isScalar(Object v) {
        return v instanceof Long || v instanceof Double || v instanceof String || v instanceof Boolean
                || v == PhpNull.INSTANCE;
    }

    /** True when every element is a plain append or the constant key matching its position. */
    private boolean buildsPacked() {
        for (int i = 0; i < values.length; i++) {
            if (spread[i]) {
                return false;
            }
            if (keys[i] == null) {
                continue;
            }
            if (!(keys[i] instanceof ConstNode c) || !(c.value() instanceof Long l) || l != i) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSpread() {
        for (boolean s : spread) {
            if (s) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (constant != null) {
            b.emitLoadConstant(constant);
            return;
        }
        // Each element wraps the array built so far: the last element's operation is outermost.
        for (int i = values.length - 1; i >= 0; i--) {
            if (spread[i]) {
                b.beginArraySpread();
            } else if (byRef[i] && !refIsThis(values[i])) {
                if (keys[i] == null) {
                    b.beginArrayAppendRef();
                } else {
                    b.beginArrayPutRef();
                }
            } else if (keys[i] == null) {
                b.beginArrayAppend();
            } else {
                b.beginArrayPut();
            }
        }
        if (hasSpread()) {
            b.emitNewArray();
        } else {
            b.emitNewArraySized(values.length, !buildsPacked());
        }
        for (int i = 0; i < values.length; i++) {
            if (spread[i]) {
                e.expr(values[i]);
                b.endArraySpread();
                continue;
            }
            if (keys[i] != null) {
                e.expr(keys[i]);
            }
            if (byRef[i]) {
                emitRefValue(e, values[i]);
                if (keys[i] == null) {
                    if (refIsThis(values[i])) {
                        b.endArrayAppend();
                    } else {
                        b.endArrayAppendRef();
                    }
                } else {
                    if (refIsThis(values[i])) {
                        b.endArrayPut();
                    } else {
                        b.endArrayPutRef();
                    }
                }
            } else {
                e.expr(values[i]);
                if (keys[i] == null) {
                    b.endArrayAppend();
                } else {
                    b.endArrayPut();
                }
            }
        }
    }

    private static boolean refIsThis(PhpNode value) {
        return value instanceof LocalVarNode lv && "this".equals(lv.varName());
    }

    private static void emitRefValue(dev.phpruntime.bytecode.BytecodeEmitter e, PhpNode value) {
        if (refIsThis(value)) {
            e.expr(value);
        } else if (value instanceof LValueNode lv) {
            e.cell(lv);
        } else {
            e.builder().beginBoxRefCopy();
            e.exprForRef(value);
            e.builder().endBoxRefCopy();
        }
    }
}
