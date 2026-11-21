package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.ConstNode;
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
    /** The key of {@code $a[]}. */
    public static final Object APPEND = new Object();

    @Child private LValueNode base;
    @Children private final PhpNode[] keyNodes;
    @Child private ArrayAccessNode access;
    /**
     * Normalised keys and hashes for the key positions that are constant literals, computed
     * once. Null when no key is constant, or when there are none (an append).
     */
    @CompilationFinal(dimensions = 1) private final PhpArray.KnownKey[] knownKeys;
    /**
     * Which uncommon write paths this site has taken. Until one is seen, compiled code
     * deoptimizes there instead of carrying string offsets, ArrayAccess objects, references
     * inside the path and array creation into every array write.
     */
    @CompilationFinal private boolean seenString;
    @CompilationFinal private boolean seenObject;
    @CompilationFinal private boolean seenRef;
    @CompilationFinal private boolean seenCreate;

    public IndexLValueNode(LValueNode base, PhpNode[] keyNodes) {
        this.base = base;
        this.keyNodes = keyNodes;
        this.knownKeys = knownKeysOf(keyNodes);
    }

    public IndexLValueNode(PhpLanguage language, LValueNode base, PhpNode[] keyNodes) {
        this(base, keyNodes);
        this.access = new ArrayAccessNode(language);
    }

    /** The constant key positions of a site, normalised and hashed once at parse time. */
    private static PhpArray.KnownKey[] knownKeysOf(PhpNode[] keyNodes) {
        PhpArray.KnownKey[] out = null;
        for (int i = 0; i < keyNodes.length; i++) {
            PhpNode n = keyNodes[i];
            if (n instanceof ConstNode c && (c.value() instanceof String || c.value() instanceof Long)) {
                if (out == null) {
                    out = new PhpArray.KnownKey[keyNodes.length];
                }
                out[i] = PhpArray.knownKey(c.value());
            }
        }
        return out;
    }

    private PhpArray.KnownKey knownKey(int at, Object key) {
        PhpArray.KnownKey[] keys = knownKeys;
        if (keys == null) {
            return null;
        }
        PhpArray.KnownKey known = keys[at];
        if (known == null) {
            return null;
        }
        Object normalized = known.key();
        return normalized == key || normalized.equals(key) ? known : null;
    }

    private record PackedKeys(Object[] baseKeys, Object[] arrayKeys) {}

    /** Keys as {@link #pack} builds them, for callers that evaluated the parts themselves. */
    public static Object[] pack(Object[] baseKeys, Object[] arrayKeys) {
        return new Object[]{new PackedKeys(baseKeys, arrayKeys)};
    }

    /**
     * A copy with no key expressions over another base, for bytecode: the operation evaluates
     * the keys and calls this node's read, write, cell and unset methods with them. Constant
     * key nodes are kept (they have no code to evaluate) so the copy can cache their hash.
     */
    public IndexLValueNode headless(PhpLanguage language, LValueNode headlessBase) {
        PhpNode[] keys = new PhpNode[keyNodes.length];
        for (int i = 0; i < keys.length; i++) {
            // A fresh ConstNode: the original belongs to the parsed AST and cannot have a
            // second parent. It carries no code, only the key value the copy caches.
            keys[i] = keyNodes[i] instanceof ConstNode c ? new ConstNode(c.value()) : null;
        }
        return new IndexLValueNode(language, headlessBase, keys).at(line());
    }

    public LValueNode base() {
        return base;
    }

    public PhpNode[] keyNodes() {
        return keyNodes;
    }

    /** The rvalue read of {@link #execute} on evaluated keys: warns when a key is missing. */
    public Object readOrWarn(VirtualFrame frame, Object[] keys) {
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

    private static Object[] baseKeys(Object[] keys) {
        return keys.length == 1 && keys[0] instanceof PackedKeys p ? p.baseKeys : NO_KEYS;
    }

    private static Object[] arrayKeys(Object[] keys) {
        return keys.length == 1 && keys[0] instanceof PackedKeys p ? p.arrayKeys : keys;
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
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
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
                if (i == keyNodes.length - 1) {
                    return access.isSet(obj, keys[i]);
                }
                if (!access.exists(obj, keys[i])) {
                    return false;
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
            Object[] ak = arrayKeys(keys);
            if (root instanceof PhpObject) {
                writeMixed(root, ak, 0, value);
                return;
            }
            if (keyNodes.length == 1 && root instanceof PhpArray array) {
                // $a[k] = v and $a[] = v: one key, no recursion, no intermediate key arrays.
                PhpArray writable = array.forWrite();
                putAt(writable, ak[0], 0, value);
                base.write(frame, bk, writable);
                return;
            }
            base.write(frame, bk, writeMixed(root, ak, 0, value));
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    /** Writes one element of an already writable array, inline when the key is a literal. */
    private void putAt(PhpArray array, Object key, int at, Object value) {
        if (key == APPEND) {
            array.appendWritable(value);
            return;
        }
        PhpArray.KnownKey known = knownKey(at, key);
        if (known != null) {
            array.putKnown(known, value);
        } else {
            array.putFast(key, value);
        }
    }

    private Object rawAt(PhpArray array, Object key, int at) {
        if (key == APPEND) {
            return null;
        }
        PhpArray.KnownKey known = knownKey(at, key);
        return known != null ? array.getRawKnown(known) : array.getRawFast(key);
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
                return mixedCell(obj, ak, 0).cell();
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
            if (root instanceof PhpObject obj && keyNodes.length == 1) {
                access.bindCell(obj, arrayKeys(keys)[0] == APPEND ? PhpNull.INSTANCE : arrayKeys(keys)[0], cell);
                return;
            }
            if (root instanceof PhpObject) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot assign by reference to an array dimension of an object", this);
            }
            PhpReference[] outRef = new PhpReference[1];
            Object updatedRoot = obtainPathCell(root, arrayKeys(keys), cell, outRef);
            base.write(frame, bk, updatedRoot);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    /** {@code $s[$i] = $v}: replaces one byte, padding with spaces past the end (php's rules). */
    @TruffleBoundary
    private String writeStringOffset(String s, Object key, Object value) {
        if (key == APPEND) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("[] operator not supported for strings", this);
        }
        long offset = PhpOps.toLong(key);
        if (offset < 0) {
            offset += s.length();
        }
        if (offset < 0 || offset > Integer.MAX_VALUE - 8) {
            PhpContext.get(this).warning("Illegal string offset " + PhpOps.toStr(key), line());
            return s;
        }
        String replacement = PhpOps.toStr(value);
        if (replacement.isEmpty()) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.VALUE_ERROR, "Cannot assign an empty string to a string offset", this);
        }
        if (replacement.length() > 1) {
            PhpContext.get(this).warning("Only the first byte will be assigned to the string offset", line());
        }
        StringBuilder out = new StringBuilder(s);
        if (offset >= out.length()) {
            out.append(" ".repeat((int) offset + 1 - out.length()));
        }
        out.setCharAt((int) offset, replacement.charAt(0));
        return out.toString();
    }

    private Object writeMixed(Object root, Object[] keys, int at, Object value) {
        Object key = keys[at];
        if (root instanceof String s && at == keyNodes.length - 1) {
            if (!seenString) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenString = true;
            }
            return writeStringOffset(s, key, value);
        }
        if (root instanceof PhpObject obj) {
            if (!seenObject) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenObject = true;
            }
            if (at == keyNodes.length - 1) {
                access.set(obj, key == APPEND ? PhpNull.INSTANCE : key, value);
            } else {
                Object raw = access.getRaw(obj, key);
                if (raw instanceof PhpReference ref) {
                    ref.setValue(writeMixed(ref.getValue(), keys, at + 1, value));
                } else {
                    if (!(raw instanceof PhpObject)) {
                        access.indirectNotice(obj);
                    }
                    writeMixed(PhpOps.copyOnAssign(raw), keys, at + 1, value);
                }
            }
            return obj;
        }
        PhpArray arr = writable(root, key);
        if (at == keyNodes.length - 1) {
            putAt(arr, key, at, value);
        } else {
            Object raw = rawAt(arr, key, at);
            if (raw instanceof PhpReference ref) {
                if (!seenRef) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenRef = true;
                }
                ref.setValue(writeMixed(ref.getValue(), keys, at + 1, value));
            } else {
                Object child = writeMixed(raw, keys, at + 1, value);
                putAt(arr, key, at, child);
            }
        }
        return arr;
    }

    public boolean usesArrayAccess(VirtualFrame frame, Object[] keys) {
        return base.read(frame, baseKeys(keys)) instanceof PhpObject;
    }

    public boolean isNested() {
        return keyNodes.length > 1;
    }

    @Override
    public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded) {
        if (isNested() && usesArrayAccess(frame, keys)) {
            PhpReference cell = obtainCell(frame, keys);
            Object current = cell.getValue();
            String result = concat(PhpOps.toStr(current == null ? PhpNull.INSTANCE : current), suffix);
            cell.setValue(result);
            return result;
        }
        return super.appendString(frame, keys, suffix, resultNeeded);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String concat(String left, String right) {
        return left.concat(right);
    }

    private record CellResult(Object root, PhpReference cell) {}

    private CellResult mixedCell(Object root, Object[] keys, int at) {
        Object key = keys[at] == APPEND ? PhpNull.INSTANCE : keys[at];
        if (root instanceof PhpObject obj) {
            Object raw = access.getRaw(obj, key);
            if (at == keyNodes.length - 1) {
                if (raw instanceof PhpReference ref) { return new CellResult(root, ref); }
                access.indirectNotice(obj);
                return new CellResult(root, new PhpReference(PhpOps.copyOnAssign(raw)));
            }
            if (raw instanceof PhpReference ref) {
                CellResult child = mixedCell(ref.getValue(), keys, at + 1);
                ref.setValue(child.root());
                return new CellResult(root, child.cell());
            }
            if (!(raw instanceof PhpObject)) { access.indirectNotice(obj); }
            return new CellResult(root, mixedCell(PhpOps.copyOnAssign(raw), keys, at + 1).cell());
        }
        PhpArray array = writable(root);
        if (at == keyNodes.length - 1) {
            PhpReference ref = keys[at] == APPEND ? array.appendRef(new PhpReference(PhpNull.INSTANCE)) : array.obtainElementRef(key);
            return new CellResult(array, ref);
        }
        Object raw = rawAt(array, key, at);
        CellResult child = mixedCell(raw instanceof PhpReference ref ? ref.getValue() : raw, keys, at + 1);
        if (raw instanceof PhpReference ref) { ref.setValue(child.root()); }
        else { putAt(array, key, at, child.root()); }
        return new CellResult(array, child.cell());
    }

    @TruffleBoundary
    private Object obtainPathCell(Object root, Object[] keys, PhpReference bindTo, PhpReference[] outRef) {
        PhpArray top = writable(root);
        PhpArray arr = top;
        for (int i = 0; i < keys.length - 1; i++) {
            Object k = keys[i];
            Object raw = k == APPEND ? null : arr.getRaw(k);
            if (raw instanceof PhpReference ref) {
                PhpArray child = writable(ref.getValue());
                ref.setValue(child);
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

    private PhpArray writable(Object v) {
        if (v instanceof PhpArray a) {
            return a.forWrite();
        }
        if (v == null || v == PhpNull.INSTANCE) {
            if (!seenCreate) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenCreate = true;
            }
            return new PhpArray();
        }
        if (v instanceof String s && s.isEmpty()) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot auto-initialize an array from an empty string", this);
        }
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
        throw new PhpError("Cannot use a scalar value as an array", this);
    }

    /** Like {@link #writable(Object)}, with the key the new array is first written under. */
    private PhpArray writable(Object v, Object key) {
        if (v instanceof PhpArray a) {
            return a.forWrite();
        }
        if (v == null || v == PhpNull.INSTANCE) {
            if (!seenCreate) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenCreate = true;
            }
            if (key == APPEND || (key instanceof Long l && l == 0L)) {
                return new PhpArray();
            }
            return PhpArray.withCapacity(8, true);
        }
        if (v instanceof String s && s.isEmpty()) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot auto-initialize an array from an empty string", this);
        }
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
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
                unsetMixed(obj, ak, 0);
            }
            return;
        }
        if (!(root instanceof PhpArray)) {
            return;
        }
        try {
            Object[] ak = arrayKeys(keys);
            if (keyNodes.length == 1) {
                PhpArray writable = ((PhpArray) root).forWrite();
                Object key = ak[0];
                PhpArray.KnownKey known = knownKey(0, key);
                if (known != null) {
                    writable.removeKnown(known);
                } else {
                    writable.removeFast(key);
                }
                base.write(frame, bk, writable);
                return;
            }
            Object result = unsetPath((PhpArray) root, ak);
            base.write(frame, bk, result);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    private Object unsetMixed(Object root, Object[] keys, int at) {
        if (root instanceof PhpObject obj) {
            if (at == keyNodes.length - 1) {
                access.unset(obj, keys[at]);
                return obj;
            }
            Object raw = access.getRaw(obj, keys[at]);
            if (raw instanceof PhpReference ref) {
                ref.setValue(unsetMixed(ref.getValue(), keys, at + 1));
            } else {
                if (!(raw instanceof PhpObject)) { access.indirectNotice(obj); }
                unsetMixed(PhpOps.copyOnAssign(raw), keys, at + 1);
            }
            return root;
        }
        if (!(root instanceof PhpArray array)) { return root; }
        array = array.forWrite();
        if (at == keyNodes.length - 1) {
            array.removeFast(keys[at]);
        } else {
            Object raw = rawAt(array, keys[at], at);
            if (raw instanceof PhpReference ref) { ref.setValue(unsetMixed(ref.getValue(), keys, at + 1)); }
            else if (raw != null) { putAt(array, keys[at], at, unsetMixed(raw, keys, at + 1)); }
        }
        return array;
    }

    @TruffleBoundary
    private Object unsetPath(PhpArray root, Object[] keys) {
        PhpArray top = root.forWrite();
        PhpArray arr = top;
        for (int i = 0; i < keys.length - 1; i++) {
            Object raw = arr.getRaw(keys[i]);
            if (raw instanceof PhpReference ref) {
                if (!(ref.getValue() instanceof PhpArray c)) {
                    return top;
                }
                PhpArray w = c.forWrite();
                ref.setValue(w);
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
    public String describe() {
        return base.describe() + "[...]";
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emitRead(e, 0);
    }

    /** mode 0: read with warning; 1: silent read (??, nested isset); 2: isset. */
    public void emitRead(dev.phpruntime.bytecode.BytecodeEmitter e, int mode) {
        if (!(base instanceof LocalVarNode lv)) {
            e.place(this, mode == 0 ? dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ : mode == 1 ? dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ_IF_SET : dev.phpruntime.bytecode.PhpBytecodeRootNode.P_ISSET, 0, null);
            return;
        }
        for (PhpNode k : keyNodes) {
            if (k == null) {
                throw e.unsupported("read of []");
            }
        }
        var b = e.builder();
        boolean single = keyNodes.length == 1;
        switch (mode) {
            case 0 -> { if (single) { b.beginIndex(); } else { b.beginIndexPath(); } }
            case 1 -> { if (single) { b.beginIndexIfSet(); } else { b.beginIndexPathIfSet(); } }
            default -> { if (single) { b.beginIssetIndex(); } else { b.beginIssetPath(); } }
        }
        b.emitReadVarIfSet(e.accessor(lv), e.info(lv));
        for (PhpNode k : keyNodes) {
            e.expr(k);
        }
        switch (mode) {
            case 0 -> { if (single) { b.endIndex(); } else { b.endIndexPath(); } }
            case 1 -> { if (single) { b.endIndexIfSet(); } else { b.endIndexPathIfSet(); } }
            default -> { if (single) { b.endIssetIndex(); } else { b.endIssetPath(); } }
        }
    }
}
