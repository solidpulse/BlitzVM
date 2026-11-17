package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.stmt.IteratorNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.SplInterfaces;

import java.util.Map;

/** {@code [1, 'k' => 2, &$x, ...$other]} or {@code array(...)}. A null key node means "next index". */
public final class ArrayLiteralNode extends PhpNode {

    @Children private final PhpNode[] keys;
    @Children private final PhpNode[] values;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;
    @CompilationFinal(dimensions = 1) private final boolean[] spread;
    @Child private IteratorNode iterator;
    /**
     * The value when every key and value is a literal: built once here and returned by every
     * execution, in every request, as an immutable array (writers copy it first).
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

    public ArrayLiteralNode(PhpLanguage language, PhpNode[] keys, PhpNode[] values, boolean[] refs, boolean[] spreads) {
        this(keys, values, refs, spreads);
        iterator = new IteratorNode(language);
    }

    /** The immutable value of an all-literal array, or null if anything must run per execution. */
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

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (constant != null) {
            return constant;
        }
        PhpArray a = new PhpArray();
        for (int i = 0; i < values.length; i++) {
            if (spread[i]) {
                unpackInto(a, values[i].execute(frame));
                continue;
            }
            Object k = keys[i] == null ? null : keys[i].execute(frame);
            if (byRef[i]) {
                PhpNode valNode = values[i];
                if (valNode instanceof LocalVarNode lv && "this".equals(lv.varName())) {
                    Object thisVal = lv.execute(frame);
                    putValue(a, k, thisVal);
                } else if (valNode instanceof LValueNode lv) {
                    PhpReference cell = lv.obtainCell(frame, lv.evalKeys(frame));
                    putRef(a, k, cell);
                } else {
                    Object raw = valNode.executeForRef(frame);
                    PhpReference cell = raw instanceof PhpReference ref ? ref : new PhpReference(PhpOps.copyOnAssign(raw));
                    putRef(a, k, cell);
                }
            } else {
                Object v = PhpOps.copyOnAssign(values[i].execute(frame));
                putValue(a, k, v);
            }
        }
        return a;
    }

    private void putValue(PhpArray a, Object k, Object v) {
        if (k == null) {
            a.append(v);
        } else {
            try {
                a.put(k, v);
            } catch (PhpError e) {
                throw e.at(this);
            }
        }
    }

    private void putRef(PhpArray a, Object k, PhpReference cell) {
        if (k == null) {
            a.appendRef(cell);
        } else {
            try {
                a.putRef(k, cell);
            } catch (PhpError e) {
                throw e.at(this);
            }
        }
    }

    private void unpackInto(PhpArray dest, Object unpacked) {
        if (unpacked instanceof PhpObject obj && SplInterfaces.has(obj, SplInterfaces.TRAVERSABLE)) {
            PhpObject it = iterator.resolve(obj);
            iterator.rewind(it);
            while (iterator.valid(it)) {
                com.oracle.truffle.api.TruffleSafepoint.poll(this);
                Object value = PhpOps.copyOnAssign(iterator.current(it));
                Object key = iterator.key(it);
                if (key instanceof String) {
                    dest.put(key, value);
                } else if (key instanceof Long) {
                    dest.append(value);
                } else {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("Keys must be of type int|string during array unpacking", this);
                }
                iterator.next(it);
            }
            return;
        }
        if (!(unpacked instanceof PhpArray src)) {
            String given = unpacked == null || unpacked == PhpNull.INSTANCE ? "null" : PhpOps.typeName(unpacked);
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Only arrays and Traversables can be unpacked, " + given + " given", this);
        }
        for (Map.Entry<Object, Object> e : src.entries()) {
            Object v = PhpOps.copyOnAssign(e.getValue());
            if (e.getKey() instanceof Long) {
                dest.append(v);
            } else {
                dest.put(e.getKey(), v);
            }
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (constant != null) {
            b.emitLoadConstant(constant);
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (byRef[i] || spread[i]) {
                throw e.unsupported("array literal with reference or spread");
            }
        }
        // Each element wraps the array built so far: the last element's operation is outermost.
        for (int i = values.length - 1; i >= 0; i--) {
            if (keys[i] == null) {
                b.beginArrayAppend();
            } else {
                b.beginArrayPut();
            }
        }
        b.emitNewArray();
        for (int i = 0; i < values.length; i++) {
            if (keys[i] == null) {
                e.expr(values[i]);
                b.endArrayAppend();
            } else {
                e.expr(keys[i]);
                e.expr(values[i]);
                b.endArrayPut();
            }
        }
    }
}
