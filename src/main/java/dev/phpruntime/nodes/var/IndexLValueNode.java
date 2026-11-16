package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpObject;

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
    @Child private ArrayAccessNode access;

    public IndexLValueNode(LValueNode base, PhpNode[] keyNodes) {
        this.base = base;
        this.keyNodes = keyNodes;
    }

    public IndexLValueNode(PhpLanguage language, LValueNode base, PhpNode[] keyNodes) {
        this(base, keyNodes);
        this.access = new ArrayAccessNode(language);
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
        try {
            return readPath(base.read(frame, baseKeys(keys)), arrayKeys(keys));
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @ExplodeLoop
    private Object readPath(Object v, Object[] keys) {
        for (int i = 0; i < keyNodes.length; i++) {
            Object k = keys[i];
            if (k == APPEND) {
                throw new PhpError("Cannot use [] for reading", this);
            }
            v = v instanceof PhpObject obj ? access.get(obj, k) : IndexReadNode.index(v, k);
            if (v == null) {
                return null;
            }
        }
        return v;
    }

    @Override
    @ExplodeLoop
    public boolean isSet(VirtualFrame frame, Object[] packed) {
        Object value = base.readIfSet(frame, baseKeys(packed));
        Object[] keys = arrayKeys(packed);
        for (int i = 0; i < keyNodes.length; i++) {
            if (value instanceof PhpObject obj) {
                if (!access.exists(obj, keys[i])) {
                    return false;
                }
                if (i == keyNodes.length - 1) {
                    return true;
                }
                value = access.get(obj, keys[i]);
            } else {
                value = IndexReadNode.index(value, keys[i]);
            }
            if (value == null || value == PhpNull.INSTANCE) {
                return false;
            }
        }
        return true;
    }

    @Override
    @ExplodeLoop
    public Object readIfSet(VirtualFrame frame, Object[] packed) {
        Object value = base.readIfSet(frame, baseKeys(packed));
        Object[] keys = arrayKeys(packed);
        for (int i = 0; i < keyNodes.length; i++) {
            if (value instanceof PhpObject obj) {
                if (!access.exists(obj, keys[i])) {
                    return null;
                }
                value = access.get(obj, keys[i]);
            } else {
                value = IndexReadNode.index(value, keys[i]);
            }
            if (value == null || value == PhpNull.INSTANCE) {
                return null;
            }
        }
        return value;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        Object[] bk = baseKeys(keys);
        if (base instanceof PropertyLValueNode plv) {
            plv.checkNotReadonlyForIndirectWrite(frame, bk);
        }
        Object root = base.read(frame, bk);
        try {
            base.write(frame, bk, writeMixed(root, arrayKeys(keys), 0, value));
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        Object[] bk = baseKeys(keys);
        if (base instanceof PropertyLValueNode plv) {
            plv.checkNotReadonlyForIndirectWrite(frame, bk);
        }
        Object root = base.read(frame, bk);
        try {
            Object[] ak = arrayKeys(keys);
            if (root instanceof PhpObject obj) {
                PhpReference cell = access.cell(obj, ak[0]);
                if (ak.length == 1) {
                    return cell;
                }
                PhpReference[] out = new PhpReference[1];
                cell.value = obtainPathCell(cell.value, java.util.Arrays.copyOfRange(ak, 1, ak.length), null, out);
                return out[0];
            }
            PhpReference[] outRef = new PhpReference[1];
            Object updatedRoot = obtainPathCell(root, arrayKeys(keys), null, outRef);
            base.write(frame, bk, updatedRoot);
            return outRef[0];
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        Object[] bk = baseKeys(keys);
        if (base instanceof PropertyLValueNode plv) {
            plv.checkNotReadonlyForIndirectWrite(frame, bk);
        }
        Object root = base.read(frame, bk);
        try {
            if (root instanceof PhpObject) {
                throw new PhpError("Cannot assign by reference to an array dimension of an object", this);
            }
            PhpReference[] outRef = new PhpReference[1];
            Object updatedRoot = obtainPathCell(root, arrayKeys(keys), cell, outRef);
            base.write(frame, bk, updatedRoot);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    private Object writeMixed(Object root, Object[] keys, int at, Object value) {
        Object key = keys[at];
        if (root instanceof PhpObject obj) {
            if (at == keys.length - 1) {
                access.set(obj, key == APPEND ? PhpNull.INSTANCE : key, value);
            } else {
                Object raw = access.getRaw(obj, key);
                if (raw instanceof PhpReference ref) {
                    ref.value = writeMixed(ref.value, keys, at + 1, value);
                } else {
                    if (!(raw instanceof PhpObject)) {
                        access.indirectNotice(obj);
                    }
                    writeMixed(PhpOps.copyOnAssign(raw), keys, at + 1, value);
                }
            }
            return obj;
        }
        PhpArray arr = writable(root);
        if (at == keys.length - 1) {
            if (key == APPEND) {
                arr.append(value);
            } else {
                arr.put(key, value);
            }
        } else {
            Object raw = key == APPEND ? null : arr.getRaw(key);
            if (raw instanceof PhpReference ref) {
                ref.value = writeMixed(ref.value, keys, at + 1, value);
            } else {
                Object child = writeMixed(raw, keys, at + 1, value);
                if (key == APPEND) {
                    arr.append(child);
                } else {
                    arr.put(key, child);
                }
            }
        }
        return arr;
    }

    public boolean usesArrayAccess(VirtualFrame frame, Object[] keys) {
        return base.read(frame, baseKeys(keys)) instanceof PhpObject;
    }

    @TruffleBoundary
    private Object obtainPathCell(Object root, Object[] keys, PhpReference bindTo, PhpReference[] outRef) {
        PhpArray top = writable(root);
        PhpArray arr = top;
        for (int i = 0; i < keys.length - 1; i++) {
            Object k = keys[i];
            Object raw = k == APPEND ? null : arr.getRaw(k);
            if (raw instanceof PhpReference ref) {
                PhpArray child = writable(ref.value);
                ref.value = child;
                arr = child;
            } else {
                PhpArray child = writable(raw);
                if (k == APPEND) {
                    arr.append(child);
                } else {
                    arr.put(k, child);
                }
                arr = child;
            }
        }
        Object last = keys[keys.length - 1];
        if (bindTo != null) {
            if (last == APPEND) {
                arr.appendRef(bindTo);
            } else {
                arr.putRef(last, bindTo);
            }
            outRef[0] = bindTo;
        } else {
            outRef[0] = last == APPEND ? arr.appendRef(new PhpReference(PhpNull.INSTANCE)) : arr.obtainElementRef(last);
        }
        return top;
    }

    @TruffleBoundary
    private Object writePath(Object root, Object[] keys, Object value) {
        PhpArray top = writable(root);
        PhpArray arr = top;
        for (int i = 0; i < keys.length - 1; i++) {
            Object k = keys[i];
            Object raw = k == APPEND ? null : arr.getRaw(k);
            if (raw instanceof PhpReference ref) {
                PhpArray child = writable(ref.value);
                ref.value = child;
                arr = child;
            } else {
                PhpArray child = writable(raw);
                if (k == APPEND) {
                    arr.append(child);
                } else {
                    arr.put(k, child);
                }
                arr = child;
            }
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
        if (base instanceof PropertyLValueNode plv) {
            plv.checkNotReadonlyForIndirectWrite(frame, bk);
        }
        Object root = base.read(frame, bk);
        if (root instanceof PhpObject obj) {
            Object[] ak = arrayKeys(keys);
            if (ak.length == 1) {
                access.unset(obj, ak[0]);
            } else {
                Object raw = access.getRaw(obj, ak[0]);
                if (raw instanceof PhpReference ref && ref.value instanceof PhpArray arr) {
                    ref.value = unsetPath(arr, java.util.Arrays.copyOfRange(ak, 1, ak.length));
                } else {
                    access.indirectNotice(obj);
                }
            }
            return;
        }
        if (!(root instanceof PhpArray)) {
            return;
        }
        try {
            Object result = unsetPath((PhpArray) root, arrayKeys(keys));
            base.write(frame, bk, result);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @TruffleBoundary
    private Object unsetPath(PhpArray root, Object[] keys) {
        PhpArray top = root.forWrite();
        PhpArray arr = top;
        for (int i = 0; i < keys.length - 1; i++) {
            Object raw = arr.getRaw(keys[i]);
            if (raw instanceof PhpReference ref) {
                if (!(ref.value instanceof PhpArray c)) {
                    return top;
                }
                PhpArray w = c.forWrite();
                ref.value = w;
                arr = w;
            } else {
                if (!(raw instanceof PhpArray c)) {
                    return top;
                }
                PhpArray w = c.forWrite();
                arr.put(keys[i], w);
                arr = w;
            }
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
