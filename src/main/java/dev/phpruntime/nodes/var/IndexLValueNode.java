package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

/**
 * {@code $a[k1][k2]...}, where a null key node means append ({@code $a[] = v}).
 * Writes walk the path making each level writable (copy-on-write, autovivifying
 * missing levels) and store the root back into the variable.
 */
public final class IndexLValueNode extends LValueNode {

    /** Marker for an append key in the evaluated key array. */
    private static final Object APPEND = new Object();

    @Child private LValueNode base;
    @Children private final PhpNode[] keyNodes;

    public IndexLValueNode(LValueNode base, PhpNode[] keyNodes) {
        this.base = base;
        this.keyNodes = keyNodes;
    }

    private record PackedKeys(Object[] baseKeys, Object[] arrayKeys) {}

    private static Object[] baseKeys(Object[] keys) {
        return keys.length == 1 && keys[0] instanceof PackedKeys p ? p.baseKeys : NO_KEYS;
    }

    private static Object[] arrayKeys(Object[] keys) {
        return keys.length == 1 && keys[0] instanceof PackedKeys p ? p.arrayKeys : keys;
    }

    @Override
    @ExplodeLoop
    public Object[] evalKeys(VirtualFrame frame) {
        Object[] baseKeys = base.evalKeys(frame);
        Object[] keys = new Object[keyNodes.length];
        for (int i = 0; i < keyNodes.length; i++) {
            keys[i] = keyNodes[i] == null ? APPEND : keyNodes[i].execute(frame);
        }
        return baseKeys == NO_KEYS ? keys : new Object[]{new PackedKeys(baseKeys, keys)};
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        return readPath(base.read(frame, baseKeys(keys)), arrayKeys(keys));
    }

    @TruffleBoundary
    private Object readPath(Object v, Object[] keys) {
        for (Object k : keys) {
            if (k == APPEND) {
                throw new PhpError("Cannot use [] for reading", this);
            }
            v = IndexReadNode.index(v, k);
            if (v == null) {
                return null;
            }
        }
        return v;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        Object[] bk = baseKeys(keys);
        Object root = base.read(frame, bk);
        base.write(frame, bk, writePath(root, arrayKeys(keys), value));
    }

    @TruffleBoundary
    private Object writePath(Object root, Object[] keys, Object value) {
        PhpArray top = writable(root);
        PhpArray arr = top;
        for (int i = 0; i < keys.length - 1; i++) {
            Object k = keys[i];
            PhpArray child = writable(k == APPEND ? null : arr.get(k));
            if (k == APPEND) {
                arr.append(child);
            } else {
                arr.put(k, child);
            }
            arr = child;
        }
        Object last = keys[keys.length - 1];
        if (last == APPEND) {
            arr.append(value);
        } else {
            arr.put(last, value);
        }
        return top;
    }

    private PhpArray writable(Object v) {
        if (v instanceof PhpArray a) {
            return a.forWrite();
        }
        if (v == null || v == PhpNull.INSTANCE) {
            return new PhpArray();
        }
        if (v instanceof String s && s.isEmpty()) {
            throw new PhpError("Cannot auto-initialize an array from an empty string", this);
        }
        throw new PhpError("Cannot use a scalar value as an array", this);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        Object[] bk = baseKeys(keys);
        Object root = base.read(frame, bk);
        if (!(root instanceof PhpArray)) {
            return;
        }
        Object result = unsetPath((PhpArray) root, arrayKeys(keys));
        base.write(frame, bk, result);
    }

    @TruffleBoundary
    private Object unsetPath(PhpArray root, Object[] keys) {
        PhpArray top = root.forWrite();
        PhpArray arr = top;
        for (int i = 0; i < keys.length - 1; i++) {
            Object child = arr.get(keys[i]);
            if (!(child instanceof PhpArray c)) {
                return top;
            }
            PhpArray w = c.forWrite();
            arr.put(keys[i], w);
            arr = w;
        }
        arr.remove(keys[keys.length - 1]);
        return top;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] keys = evalKeys(frame);
        Object v = read(frame, keys);
        if (v == null) {
            warnMissing(arrayKeys(keys));
            return PhpNull.INSTANCE;
        }
        return v;
    }

    @TruffleBoundary
    private void warnMissing(Object[] keys) {
        Object k = PhpArray.normalizeKey(keys[keys.length - 1]);
        String shown = k instanceof String ? "\"" + k + "\"" : PhpOps.toStr(k);
        PhpContext.get(this).warning("Undefined array key " + shown, line());
    }

    @Override
    public String describe() {
        return base.describe() + "[...]";
    }
}
