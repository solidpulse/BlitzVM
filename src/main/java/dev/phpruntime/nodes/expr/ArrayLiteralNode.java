package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.Map;

/** {@code [1, 'k' => 2, &$x, ...$other]} or {@code array(...)}. A null key node means "next index". */
public final class ArrayLiteralNode extends PhpNode {

    @Children private final PhpNode[] keys;
    @Children private final PhpNode[] values;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;
    @CompilationFinal(dimensions = 1) private final boolean[] spread;

    public ArrayLiteralNode(PhpNode[] keys, PhpNode[] values) {
        this(keys, values, new boolean[values.length], new boolean[values.length]);
    }

    public ArrayLiteralNode(PhpNode[] keys, PhpNode[] values, boolean[] byRef, boolean[] spread) {
        this.keys = keys;
        this.values = values;
        this.byRef = byRef;
        this.spread = spread;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
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

    @TruffleBoundary
    private void unpackInto(PhpArray dest, Object unpacked) {
        if (!(unpacked instanceof PhpArray src)) {
            String given = unpacked == null || unpacked == PhpNull.INSTANCE ? "null" : PhpOps.typeName(unpacked);
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
}
