package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * PHP's packed list or compact ordered hash. Keys are normalised to {@code Long} or {@code String}
 * exactly like PHP does ("5" becomes 5, true becomes 1, 1.7 becomes 1).
 *
 * <p>PHP arrays have value semantics. We implement that with copy-on-write: an
 * assignment calls {@link #share()} which bumps a share counter instead of copying;
 * the first write to a shared array makes a private copy. Writers must always use
 * the array returned by {@link #forWrite()}.
 */
public final class PhpArray implements TruffleObject {

    private static final Object[] EMPTY_VALUES = new Object[0];
    private static final int BOX_CACHE_LIMIT = 8192;
    private static final Object[] NO_BOX_CACHE = new Object[0];
    /**
     * A null keys array means packed: values[0..size) have implicit integer keys. Packed arrays
     * whose elements are all PHP ints or all PHP floats store them in {@link #longValues} or
     * {@link #doubleValues}; exactly one of the three arrays is live at a time. Hashed arrays
     * always use {@link #values}.
     */
    private Object[] values = EMPTY_VALUES;
    private long[] longValues;
    private double[] doubleValues;
    /**
     * Boxed elements of a typed array, created on the first read: a primitive stored in a
     * frame slot is consumed through an Object, and without this every element read would
     * allocate the box again. Null means "not created yet"; a zero-length array means the
     * array is too large to cache and reads box on every access.
     */
    private Object[] boxedValues;
    private Object[] keys;
    private int[] hashes;
    /** Open-addressed index: 0 is empty, -1 is deleted, otherwise slot + 1. */
    private int[] index;
    private int size;
    private int used;
    private int indexUsed;
    private long nextIndex;
    private boolean nextIndexInitialized;
    /** Number of extra owners. 0 means exclusively owned and safe to mutate in place. */
    private int shared;
    /** Internal pointer as a live slot, or -1 when invalid. */
    private int pointer = -1;
    /**
     * Never written again, and safe to share between requests and threads: the value of a
     * constant array literal, built once at parse time (php's immutable arrays). Owners are
     * not counted, and {@link #forWrite()} always copies.
     */
    private boolean immutable;

    public PhpArray() {
    }

    /**
     * An empty array with room for {@code expected} entries, in the hash layout when the first keys
     * are known to be strings. Semantics are those of {@link #PhpArray()}; only the allocation
     * changes, so callers that build a known number of entries avoid converting and doubling.
     */
    public static PhpArray withCapacity(int expected, boolean stringKeys) {
        PhpArray a = new PhpArray();
        if (expected <= 0) {
            return a;
        }
        // Grow to the same power-of-two bucket the array would reach by doubling, but in one
        // allocation. Keeping 8 as the minimum also leaves the same room for later appends that
        // the old first conversion did.
        int capacity = Math.max(8, Integer.highestOneBit(expected - 1) << 1);
        a.values = new Object[capacity];
        if (stringKeys) {
            a.keys = new Object[capacity];
            a.hashes = new int[capacity];
            a.index = new int[capacity * 2];
        }
        return a;
    }

    /** Makes this array immutable; every array nested in it must already be immutable. */
    public PhpArray freeze() {
        immutable = true;
        return this;
    }

    public boolean isImmutable() {
        return immutable;
    }

    /** Called on assignment; returns the same instance with an extra owner. */
    public PhpArray share() {
        if (!immutable) {
            shared++;
        }
        return this;
    }

    /** Releases a temporary foreach owner; forWrite may already have consumed the last share. */
    public void releaseShare() {
        if (shared > 0) {
            shared--;
        }
    }

    /**
     * Returns an instance safe to mutate: this one if unshared, otherwise a deep-enough copy.
     * The unshared check stays inline in compiled code; only the copy is behind a boundary.
     */
    public PhpArray forWrite() {
        if (shared == 0 && !immutable) {
            return this;
        }
        return copyForWrite();
    }

    @TruffleBoundary
    private PhpArray copyForWrite() {
        if (!immutable) {
            shared--;
        }
        PhpArray result = new PhpArray();
        if (longValues != null) {
            result.values = null;
            result.longValues = Arrays.copyOf(longValues, longValues.length);
        } else if (doubleValues != null) {
            result.values = null;
            result.doubleValues = Arrays.copyOf(doubleValues, doubleValues.length);
        } else {
            result.values = Arrays.copyOf(values, values.length);
            for (int pos = 0; pos < used; pos++) {
                if (result.values[pos] instanceof PhpArray inner) {
                    inner.share();
                }
            }
        }
        if (keys != null) {
            result.keys = Arrays.copyOf(keys, keys.length);
            result.hashes = Arrays.copyOf(hashes, hashes.length);
            result.index = Arrays.copyOf(index, index.length);
        }
        result.size = size;
        result.used = used;
        result.indexUsed = indexUsed;
        result.nextIndex = nextIndex;
        result.nextIndexInitialized = nextIndexInitialized;
        result.pointer = pointer;
        return result;
    }

    @TruffleBoundary
    public static Object normalizeKey(Object key) {
        if (key instanceof Long) {
            return key;
        }
        if (key instanceof String s) {
            if (isCanonicalInt(s)) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                    return s;
                }
            }
            return s;
        }
        if (key instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (key instanceof Double d) {
            return (long) d.doubleValue();
        }
        if (key == PhpNull.INSTANCE) {
            return "";
        }
        String typeDesc = key instanceof PhpObject o ? o.phpClass().name() : PhpOps.typeName(key);
        throw new PhpError(PhpError.TYPE_ERROR, "Cannot access offset of type " + typeDesc + " on array", null);
    }

    @TruffleBoundary
    private static boolean isCanonicalInt(String s) {
        int n = s.length();
        if (n == 0 || n > 20) {
            return false;
        }
        int i = s.charAt(0) == '-' ? 1 : 0;
        if (i == n) {
            return false;
        }
        if (s.charAt(i) == '0' && n > i + 1) {
            return false; // "01" stays a string key
        }
        if (s.equals("-0")) {
            return false;
        }
        for (; i < n; i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public Object get(Object key) {
        Object v = getRaw(key);
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    /**
     * The element at a non-negative int key of a packed array, read inline in compiled code (no
     * boundary). Null when the array is hashed, the key is outside it, or the slot holds a
     * reference cell: callers then take the general path.
     */
    public Object packedGetOrNull(long key) {
        if (keys == null && key >= 0 && key < size) {
            int i = (int) key;
            long[] longs = longValues;
            if (longs != null) {
                return boxedLong(longs, i);
            }
            double[] doubles = doubleValues;
            if (doubles != null) {
                return boxedDouble(doubles, i);
            }
            Object v = values[i];
            if (!(v instanceof PhpReference)) {
                return v;
            }
        }
        return null;
    }

    /**
     * Overwrites an existing element of an unshared, mutable packed array inline (no boundary).
     * False when that does not apply (hashed, shared, immutable, key outside, reference slot,
     * or a value that does not fit the typed storage), and nothing is written: callers then
     * take the general path.
     */
    public boolean packedSetExisting(long key, Object value) {
        if (keys == null && shared == 0 && !immutable && key >= 0 && key < size) {
            int i = (int) key;
            long[] longs = longValues;
            if (longs != null) {
                if (value instanceof Long l) {
                    longs[i] = l;
                    cacheBoxed(i, value);
                    return true;
                }
                return false;
            }
            double[] doubles = doubleValues;
            if (doubles != null) {
                if (value instanceof Double d) {
                    doubles[i] = d;
                    cacheBoxed(i, value);
                    return true;
                }
                return false;
            }
            Object[] vs = values;
            Object old = vs[i];
            if (old != null && !(old instanceof PhpReference)) {
                vs[i] = value;
                return true;
            }
        }
        return false;
    }

    /**
     * A compound assignment whose result is discarded ({@code $a[$i] += $b[$j];}). When both
     * storage and the right-hand value fit, the arithmetic and the store stay primitive, so
     * the statement produces no box at all; otherwise null sends the caller to the general
     * path (which reads and writes through Objects).
     */
    public Object packedCompoundDiscard(long key, int op, Object value) {
        if (keys != null || shared != 0 || immutable || key < 0 || key >= size) {
            return null;
        }
        int i = (int) key;
        boolean add = op == PhpArith.Op.ADD.ordinal();
        boolean sub = op == PhpArith.Op.SUB.ordinal();
        boolean mul = op == PhpArith.Op.MUL.ordinal();
        if (!add && !sub && !mul) {
            return null;
        }
        long[] longs = longValues;
        if (longs != null) {
            if (!(value instanceof Long b)) {
                return null;
            }
            long a = longs[i];
            long r;
            if (add) {
                r = a + b;
                if (((a ^ r) & (b ^ r)) < 0) {
                    return null;
                }
            } else if (sub) {
                r = a - b;
                if (((a ^ b) & (a ^ r)) < 0) {
                    return null;
                }
            } else {
                r = a * b;
                if (Math.multiplyHigh(a, b) != (r >> 63)) {
                    return null;
                }
            }
            longs[i] = r;
            invalidateBoxed(i);
            return PhpNull.INSTANCE;
        }
        double[] doubles = doubleValues;
        if (doubles != null) {
            double a = doubles[i];
            double b;
            if (value instanceof Long l) {
                b = l;
            } else if (value instanceof Double d) {
                b = d;
            } else {
                return null;
            }
            doubles[i] = add ? a + b : sub ? a - b : a * b;
            invalidateBoxed(i);
            return PhpNull.INSTANCE;
        }
        return null;
    }

    @TruffleBoundary
    public Object getRaw(Object key) {
        int pos = find(normalizeKey(key));
        return pos < 0 ? null : rawValueAt(pos);
    }

    @TruffleBoundary
    public boolean containsKey(Object key) {
        return find(normalizeKey(key)) >= 0;
    }

    /**
     * A key already normalized, with its hash: what a site whose key is a constant computes once
     * instead of on every access.
     */
    public record KnownKey(Object key, int hash) {
    }

    @TruffleBoundary
    public static KnownKey knownKey(Object key) {
        Object k = normalizeKey(key);
        return new KnownKey(k, hash(k));
    }

    /** {@link #get} for a key from {@link #knownKey}. */
    @TruffleBoundary
    public Object get(KnownKey key) {
        int pos = find(key.key(), key.hash());
        Object v = pos < 0 ? null : rawValueAt(pos);
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    @TruffleBoundary
    public void put(Object key, Object value) {
        Object k = normalizeKey(key);
        store(k, value, false);
    }

    @TruffleBoundary
    public void putRef(Object key, PhpReference ref) {
        store(normalizeKey(key), ref, true);
    }

    /**
     * The cell for {@code key} when the key is present, creating it in place, or null when the
     * key is absent. One lookup for foreach by reference, which would otherwise test and then
     * rewrite the element.
     */
    @TruffleBoundary
    public PhpReference elementRefIfPresent(Object key) {
        generalize();
        Object k = normalizeKey(key);
        int pos = find(k);
        if (pos < 0) {
            return null;
        }
        Object existing = values[pos];
        if (existing instanceof PhpReference ref) {
            ref.ensureDefined();
            return ref;
        }
        PhpReference ref = new PhpReference(existing == null ? PhpNull.INSTANCE : existing);
        values[pos] = ref;
        return ref;
    }

    @TruffleBoundary
    public PhpReference obtainElementRef(Object key) {
        generalize();
        Object k = normalizeKey(key);
        int pos = find(k);
        Object existing = pos < 0 ? null : values[pos];
        if (existing instanceof PhpReference ref) {
            ref.ensureDefined();
            return ref;
        }
        PhpReference ref = new PhpReference(existing == null ? PhpNull.INSTANCE : existing);
        store(k, ref, true);
        return ref;
    }

    @TruffleBoundary
    public PhpReference appendRef(PhpReference ref) {
        append(ref);
        return ref;
    }

    @TruffleBoundary
    public void append(Object value) {
        if (keys == null && nextIndex == size) {
            insertPacked(value);
            updateNextIndex(size - 1L);
        } else {
            if (nextIndex == Long.MAX_VALUE && find(nextIndex) >= 0) {
                throw new PhpError("Cannot add element to the array as the next element is already occupied", null);
            }
            store(nextIndex, value, true);
        }
    }

    @TruffleBoundary
    public void remove(Object key) {
        int pos = find(normalizeKey(key));
        if (pos >= 0) {
            delete(pos);
        }
    }

    @TruffleBoundary
    public int size() {
        return size;
    }

    @TruffleBoundary
    public List<Object> keys() {
        List<Object> out = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            out.add(keyAt(pos));
        }
        return out;
    }

    @TruffleBoundary
    public List<Object> values() {
        List<Object> out = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            out.add(valueAt(pos));
        }
        return out;
    }

    /** Whether any key is a string; a packed array has none. */
    @TruffleBoundary
    public boolean hasStringKey() {
        if (keys == null) {
            return false;
        }
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            if (keys[pos] instanceof String) {
                return true;
            }
        }
        return false;
    }

    /** The raw values (reference cells kept) in order, as a new array: {@link #rawValues} without a list. */
    @TruffleBoundary
    public Object[] rawValueArray() {
        Object[] out = new Object[size];
        int i = 0;
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            out[i++] = rawValueAt(pos);
        }
        return out;
    }

    @TruffleBoundary
    public List<Object> rawValues() {
        List<Object> out = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            out.add(rawValueAt(pos));
        }
        return out;
    }

    /** Snapshot of entries (unwrapped) for builtin callers. Foreach uses the cursor instead. */
    @TruffleBoundary
    public List<Map.Entry<Object, Object>> entries() {
        List<Map.Entry<Object, Object>> list = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            list.add(Map.entry(keyAt(pos), valueAt(pos)));
        }
        return list;
    }

    /** Snapshot of raw entries preserving {@link PhpReference} cells. */
    @TruffleBoundary
    public List<Map.Entry<Object, Object>> rawEntries() {
        List<Map.Entry<Object, Object>> list = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            list.add(Map.entry(keyAt(pos), rawValueAt(pos)));
        }
        return list;
    }

    /**
     * A copy of the values of a packed array (implicit keys 0..size-1), or null when the array
     * has explicit keys. Callers that only need values then avoid one wrapper per element.
     */
    @TruffleBoundary
    public Object[] packedValues() {
        if (keys != null) {
            return null;
        }
        Object[] out = new Object[size];
        for (int i = 0; i < size; i++) {
            out[i] = valueAt(i);
        }
        return out;
    }

    /**
     * An owner of this array for a walk that can run PHP, so iterating with {@link #firstPos}
     * is as safe as {@link #entries}: a callback that writes the array copies it first and the
     * cursor keeps php's by-value view. Release with {@link #releaseShare} when the walk ends.
     * The view must be the array the walk holds; do not keep it after the release.
     */
    @TruffleBoundary
    public PhpArray walkView() {
        return share();
    }

    /**
     * Iterates entries in array order without building a snapshot. Only for loops that cannot
     * modify this array and cannot run PHP, because a modification would move the cursor.
     */
    @TruffleBoundary
    public void forEachEntry(java.util.function.BiConsumer<Object, Object> action) {
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            action.accept(keyAt(pos), valueAt(pos));
        }
    }

    /**
     * Room for entries about to be appended to a packed array, so building a known size does not
     * grow by doubling. No semantic change; a shared array is never modified in place.
     */
    @TruffleBoundary
    public void reserve(int additional) {
        if (keys != null || additional <= 0 || shared != 0 || immutable) {
            return;
        }
        int needed = size + additional;
        long[] longs = longValues;
        if (longs != null) {
            if (needed > longs.length) {
                longValues = Arrays.copyOf(longs, needed);
            }
            return;
        }
        double[] doubles = doubleValues;
        if (doubles != null) {
            if (needed > doubles.length) {
                doubleValues = Arrays.copyOf(doubles, needed);
            }
            return;
        }
        if (needed > values.length) {
            values = Arrays.copyOf(values, needed);
        }
    }

    /**
     * Appends one value into room already reserved by {@link #withCapacity}, with the field
     * updates of {@link #append} but none of its boundary: callers that build a presized packed
     * result in a compiled loop use it. The caller guarantees a packed array with spare capacity.
     */
    public void appendReserved(Object value) {
        int capacity = valueCapacity();
        assert keys == null && size < capacity && !immutable : "appendReserved without reserved room";
        long[] longs = longValues;
        if (longs != null) {
            if (value instanceof Long l) {
                longs[size] = l;
                cacheBoxed(size, value);
            } else {
                appendReservedSlow(value);
                return;
            }
        } else {
            double[] doubles = doubleValues;
            if (doubles != null) {
                if (value instanceof Double d) {
                    doubles[size] = d;
                    cacheBoxed(size, value);
                } else {
                    appendReservedSlow(value);
                    return;
                }
            } else if (size == 0 && value instanceof Long l) {
                long[] created = new long[values.length];
                created[0] = l;
                longValues = created;
                values = null;
            } else if (size == 0 && value instanceof Double d) {
                double[] created = new double[values.length];
                created[0] = d;
                doubleValues = created;
                values = null;
            } else {
                values[size] = value;
            }
        }
        if (pointer < 0) {
            pointer = size;
        }
        used = ++size;
        if (!nextIndexInitialized || size - 1 >= nextIndex) {
            nextIndex = size;
            nextIndexInitialized = true;
        }
    }

    @TruffleBoundary
    private void appendReservedSlow(Object value) {
        generalize();
        values[size] = value;
        if (pointer < 0) {
            pointer = size;
        }
        used = ++size;
        if (!nextIndexInitialized || size - 1 >= nextIndex) {
            nextIndex = size;
            nextIndexInitialized = true;
        }
    }

    /** Length of the live storage array. */
    private int valueCapacity() {
        long[] longs = longValues;
        if (longs != null) {
            return longs.length;
        }
        double[] doubles = doubleValues;
        return doubles != null ? doubles.length : values.length;
    }

    /**
     * True when converting an element to a string can run PHP: an object's __toString(), or the
     * "Array to string conversion" warning of a nested array reaching a user error handler.
     */
    @TruffleBoundary
    public boolean hasValueThatCanRunPhp() {
        if (longValues != null || doubleValues != null) {
            return false;
        }
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            Object v = values[pos];
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            if (v instanceof PhpObject || v instanceof PhpArray) {
                return true;
            }
        }
        return false;
    }

    /**
     * implode(). With one element the result is the element itself, so no builder and no copy
     * (php returns the string unchanged). With no object or array value, converting to string cannot
     * run PHP, so the array cannot change during the walk and no snapshot is needed; object values
     * keep a snapshot because a __toString() method may modify the array, which is php's
     * by-value behaviour. One boundary call covers the whole walk, and the builder is sized
     * from the element lengths to avoid growth copies.
     */
    @TruffleBoundary
    public String join(String glue) {
        if (size == 0) {
            return "";
        }
        if (size == 1) {
            return PhpOps.toStr(valueAt(firstPos()));
        }
        if (!hasValueThatCanRunPhp()) {
            long chars = (long) glue.length() * (size - 1);
            for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
                chars += estimateText(valueAt(pos));
            }
            if (chars > Integer.MAX_VALUE - 16) {
                chars = Integer.MAX_VALUE - 16;
            }
            StringBuilder sb = new StringBuilder((int) chars);
            boolean first = true;
            for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
                if (!first) {
                    sb.append(glue);
                }
                first = false;
                sb.append(PhpOps.toStr(valueAt(pos)));
            }
            return sb.toString();
        }
        Object[] snapshot = new Object[size];
        int n = 0;
        long chars = (long) glue.length() * (size - 1);
        for (int pos = firstPos(); pos >= 0 && n < size; pos = nextPos(pos)) {
            Object v = rawValueAt(pos);
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            snapshot[n++] = v;
            chars += v instanceof String s ? s.length() : 12;
        }
        if (chars > Integer.MAX_VALUE - 16) {
            chars = Integer.MAX_VALUE - 16;
        }
        StringBuilder sb = new StringBuilder((int) chars);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(glue);
            }
            Object v = snapshot[i];
            sb.append(v instanceof String str ? str : PhpOps.toStr(v));
        }
        return sb.toString();
    }

    private static long estimateText(Object v) {
        if (v instanceof String s) {
            return s.length();
        }
        if (v instanceof PhpReference ref) {
            return ref.getValue() instanceof String s ? s.length() : 12;
        }
        return 12;
    }

    @TruffleBoundary
    public Object removeLast() {
        if (size == 0) {
            return PhpNull.INSTANCE;
        }
        int pos = lastPos();
        Object lastKey = keyAt(pos);
        Object v = rawValueAt(pos);
        delete(pos);
        // PHP only rewinds the append counter when popping its most recent integer key.
        // Unset history and negative keys survive; MAX_VALUE is a saturated counter.
        if (lastKey instanceof Long l && l >= nextIndex - 1) {
            nextIndex = l;
        }
        pointer = firstPos();
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    @TruffleBoundary
    public Object removeFirst() {
        if (size == 0) {
            return PhpNull.INSTANCE;
        }
        int pos = firstPos();
        Object v = rawValueAt(pos);
        delete(pos);
        reindex();
        pointer = firstPos();
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    /** Renumber integer keys from 0, keeping string keys (used by sort, array_shift...). */
    @TruffleBoundary
    public void reindex() {
        assert !immutable : "write to an immutable array";
        if (keys == null) {
            nextIndex = size;
            nextIndexInitialized = true;
            return;
        }
        PhpArray fresh = new PhpArray();
        int newPointer = -1;
        long i = 0;
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            if (pos == pointer) {
                newPointer = fresh.used;
            }
            if (keys[pos] instanceof Long) {
                fresh.put(i++, values[pos]);
            } else {
                fresh.put(keys[pos], values[pos]);
            }
        }
        values = fresh.values;
        longValues = fresh.longValues;
        doubleValues = fresh.doubleValues;
        boxedValues = fresh.boxedValues;
        keys = fresh.keys;
        hashes = fresh.hashes;
        index = fresh.index;
        used = fresh.used;
        indexUsed = fresh.indexUsed;
        pointer = newPointer;
        nextIndex = i;
        nextIndexInitialized = true;
    }

    @TruffleBoundary
    public boolean isList() {
        if (keys == null) {
            return true;
        }
        long i = 0;
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            Object k = keys[pos];
            if (!(k instanceof Long l) || l != i++) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static PhpArray ofList(List<?> values) {
        PhpArray a = new PhpArray();
        int n = values.size();
        boolean allLongs = n > 0;
        boolean allDoubles = n > 0;
        for (int i = 0; i < n && (allLongs || allDoubles); i++) {
            Object v = values.get(i);
            allLongs &= v instanceof Long;
            allDoubles &= v instanceof Double;
        }
        if (allLongs) {
            long[] longs = new long[n];
            for (int i = 0; i < n; i++) {
                longs[i] = (Long) values.get(i);
            }
            a.values = null;
            a.longValues = longs;
        } else if (allDoubles) {
            double[] doubles = new double[n];
            for (int i = 0; i < n; i++) {
                doubles[i] = (Double) values.get(i);
            }
            a.values = null;
            a.doubleValues = doubles;
        } else {
            a.values = values.toArray();
        }
        a.size = a.used = n;
        a.nextIndex = a.size;
        a.nextIndexInitialized = a.size != 0;
        a.pointer = a.size == 0 ? -1 : 0;
        return a;
    }

    // ---------------------------------------------------------------- storage

    @TruffleBoundary
    private int find(Object key) {
        if (keys == null) {
            return findPacked(key);
        }
        return find(key, hash(key));
    }

    private int findPacked(Object key) {
        if (key instanceof Long l && l >= 0 && l < size) {
            return (int) l.longValue();
        }
        return -1;
    }

    /** {@link #find(Object)} with the key's {@link #hash} already computed. */
    @TruffleBoundary
    private int find(Object key, int hash) {
        if (keys == null) {
            return findPacked(key);
        }
        int bucket = hash & (index.length - 1);
        while (index[bucket] != 0) {
            int pos = index[bucket] - 1;
            if (pos >= 0 && hashes[pos] == hash && key.equals(keys[pos])) {
                return pos;
            }
            bucket = (bucket + 1) & (index.length - 1);
        }
        return -1;
    }

    @TruffleBoundary
    private void store(Object key, Object value, boolean replaceRef) {
        assert !immutable : "write to an immutable array";
        if (keys == null && key instanceof Long l && l >= 0 && l <= size) {
            int pos = (int) l.longValue();
            if (pos == size) {
                insertPacked(value);
            } else {
                replace(pos, value, replaceRef);
            }
            updateNextIndex(l);
            return;
        }
        if (keys == null) {
            toHash();
        }
        int hash = hash(key);
        int bucket = hash & (index.length - 1);
        int deletedBucket = -1;
        while (index[bucket] != 0) {
            int pos = index[bucket] - 1;
            if (pos >= 0 && hashes[pos] == hash && key.equals(keys[pos])) {
                replace(pos, value, replaceRef);
                if (key instanceof Long l) {
                    updateNextIndex(l);
                }
                return;
            }
            if (pos < 0 && deletedBucket < 0) {
                deletedBucket = bucket;
            }
            bucket = (bucket + 1) & (index.length - 1);
        }
        if (used == values.length || indexUsed >= index.length * 3 / 4) {
            resizeHash(size >= values.length * 3 / 4 ? values.length * 2 : values.length);
            bucket = hash & (index.length - 1);
            while (index[bucket] != 0) {
                bucket = (bucket + 1) & (index.length - 1);
            }
        } else if (deletedBucket >= 0) {
            bucket = deletedBucket;
        }
        if (index[bucket] == 0) {
            indexUsed++;
        }
        index[bucket] = used + 1;
        keys[used] = key;
        values[used] = value;
        hashes[used] = hash;
        if (pointer < 0) {
            pointer = used;
        }
        used++;
        size++;
        if (key instanceof Long l) {
            updateNextIndex(l);
        }
    }

    @TruffleBoundary
    private void replace(int pos, Object value, boolean replaceRef) {
        assert !immutable : "write to an immutable array";
        long[] longs = longValues;
        if (longs != null) {
            if (value instanceof Long l) {
                longs[pos] = l;
                cacheBoxed(pos, value);
                return;
            }
            generalize();
        } else {
            double[] doubles = doubleValues;
            if (doubles != null) {
                if (value instanceof Double d) {
                    doubles[pos] = d;
                    cacheBoxed(pos, value);
                    return;
                }
                generalize();
            }
        }
        if (!replaceRef && values[pos] instanceof PhpReference ref && !(value instanceof PhpReference)) {
            ref.setValue(value);
        } else {
            values[pos] = value;
        }
    }

    @TruffleBoundary
    private void insertPacked(Object value) {
        assert !immutable : "write to an immutable array";
        long[] longs = longValues;
        if (longs != null) {
            if (!(value instanceof Long l)) {
                generalize();
                insertPacked(value);
                return;
            }
            if (size == longs.length) {
                longs = Arrays.copyOf(longs, Math.max(8, size * 2));
                longValues = longs;
            }
            longs[size] = l;
            cacheBoxed(size, value);
        } else {
            double[] doubles = doubleValues;
            if (doubles != null) {
                if (!(value instanceof Double d)) {
                    generalize();
                    insertPacked(value);
                    return;
                }
                if (size == doubles.length) {
                    doubles = Arrays.copyOf(doubles, Math.max(8, size * 2));
                    doubleValues = doubles;
                }
                doubles[size] = d;
                cacheBoxed(size, value);
            } else if (size == 0 && value instanceof Long l) {
                long[] created = new long[Math.max(8, values.length)];
                created[0] = l;
                longValues = created;
                values = null;
            } else if (size == 0 && value instanceof Double d) {
                double[] created = new double[Math.max(8, values.length)];
                created[0] = d;
                doubleValues = created;
                values = null;
            } else {
                if (size == values.length) {
                    values = Arrays.copyOf(values, Math.max(8, size * 2));
                }
                values[size] = value;
            }
        }
        if (pointer < 0) {
            pointer = size;
        }
        used = ++size;
    }

    /** Converts packed typed storage to boxed {@link #values}; positions and capacity are kept. */
    @TruffleBoundary
    private void generalize() {
        long[] longs = longValues;
        if (longs != null) {
            Object[] boxed = new Object[longs.length];
            Object[] cached = boxedValues;
            for (int i = 0; i < size; i++) {
                Object v = cached != null && i < cached.length ? cached[i] : null;
                boxed[i] = v != null ? v : Long.valueOf(longs[i]);
            }
            values = boxed;
            longValues = null;
            boxedValues = null;
            return;
        }
        double[] doubles = doubleValues;
        if (doubles != null) {
            Object[] boxed = new Object[doubles.length];
            Object[] cached = boxedValues;
            for (int i = 0; i < size; i++) {
                Object v = cached != null && i < cached.length ? cached[i] : null;
                boxed[i] = v != null ? v : Double.valueOf(doubles[i]);
            }
            values = boxed;
            doubleValues = null;
            boxedValues = null;
        }
    }

    @TruffleBoundary
    private void updateNextIndex(long key) {
        if (!nextIndexInitialized || key >= nextIndex) {
            nextIndex = key == Long.MAX_VALUE ? key : key + 1;
            nextIndexInitialized = true;
        }
    }

    @TruffleBoundary
    private static int hash(Object key) {
        int h = key.hashCode();
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return h;
    }

    @TruffleBoundary
    private void toHash() {
        generalize();
        int capacity = Math.max(8, values.length);
        values = Arrays.copyOf(values, capacity);
        keys = new Object[capacity];
        hashes = new int[capacity];
        index = new int[Integer.highestOneBit(capacity - 1) << 2];
        for (int pos = 0; pos < used; pos++) {
            keys[pos] = (long) pos;
            hashes[pos] = hash(keys[pos]);
            indexSlot(pos);
        }
        indexUsed = size;
    }

    /** Compacts deleted slots and rebuilds the index, preserving the pointed-to element. */
    @TruffleBoundary
    private void resizeHash(int capacity) {
        Object[] newKeys = new Object[capacity];
        Object[] newValues = new Object[capacity];
        int[] newHashes = new int[capacity];
        int dest = 0;
        int newPointer = -1;
        for (int pos = 0; pos < used; pos++) {
            if (keys[pos] != null) {
                if (pointer == pos) {
                    newPointer = dest;
                }
                newKeys[dest] = keys[pos];
                newValues[dest] = values[pos];
                newHashes[dest++] = hashes[pos];
            }
        }
        keys = newKeys;
        values = newValues;
        hashes = newHashes;
        used = size;
        pointer = newPointer;
        index = new int[Integer.highestOneBit(capacity - 1) << 2];
        for (int pos = 0; pos < used; pos++) {
            indexSlot(pos);
        }
        indexUsed = size;
    }

    @TruffleBoundary
    private void indexSlot(int pos) {
        int bucket = hashes[pos] & (index.length - 1);
        while (index[bucket] != 0) {
            bucket = (bucket + 1) & (index.length - 1);
        }
        index[bucket] = pos + 1;
    }

    @TruffleBoundary
    private void delete(int pos) {
        assert !immutable : "write to an immutable array";
        if (keys == null && pos != size - 1) {
            toHash();
        }
        if (pointer == pos) {
            pointer = nextPos(pos);
        }
        if (keys == null) {
            if (longValues == null && doubleValues == null) {
                values[pos] = null;
            } else {
                invalidateBoxed(pos);
            }
            size--;
            used = size;
        } else {
            values[pos] = null;
            size--;
            int bucket = hashes[pos] & (index.length - 1);
            while (index[bucket] != pos + 1) {
                bucket = (bucket + 1) & (index.length - 1);
            }
            index[bucket] = -1;
            keys[pos] = null;
            while (used > 0 && keys[used - 1] == null) {
                used--;
            }
        }
    }

    // ---------------------------------------------------------------- cursor

    /**
     * Cursor positions are valid while the array is held as a shared, by-value snapshot.
     * These run inside compiled foreach loops: field reads only, so no boundary.
     */
    public int firstPos() {
        return nextPos(-1);
    }

    public int nextPos(int pos) {
        for (int next = pos + 1; next < used; next++) {
            if (keys == null || keys[next] != null) {
                return next;
            }
        }
        return -1;
    }

    @TruffleBoundary
    public int lastPos() {
        return used - 1;
    }

    @TruffleBoundary
    public int prevPos(int pos) {
        for (int prev = pos - 1; prev >= 0; prev--) {
            if (keys == null || keys[prev] != null) {
                return prev;
            }
        }
        return -1;
    }

    public Object keyAt(int pos) {
        return keys == null ? (long) pos : keys[pos];
    }

    /** Value at a cursor position, unwrapping a reference cell like {@link #entries()}. */
    public Object valueAt(int pos) {
        Object v = rawValueAt(pos);
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    public Object rawValueAt(int pos) {
        long[] longs = longValues;
        if (longs != null) {
            return boxedLong(longs, pos);
        }
        double[] doubles = doubleValues;
        if (doubles != null) {
            return boxedDouble(doubles, pos);
        }
        return values[pos];
    }

    /** The box for one element, cached after the first read and updated by writes. */
    private Object boxedLong(long[] longs, int pos) {
        Object[] boxed = boxedValues;
        if (boxed == null) {
            boxed = createBoxCache(longs.length);
        }
        if (pos < boxed.length) {
            Object v = boxed[pos];
            if (v != null) {
                return v;
            }
            Long created = Long.valueOf(longs[pos]);
            boxed[pos] = created;
            return created;
        }
        return Long.valueOf(longs[pos]);
    }

    private Object boxedDouble(double[] doubles, int pos) {
        Object[] boxed = boxedValues;
        if (boxed == null) {
            boxed = createBoxCache(doubles.length);
        }
        if (pos < boxed.length) {
            Object v = boxed[pos];
            if (v != null) {
                return v;
            }
            Double created = Double.valueOf(doubles[pos]);
            boxed[pos] = created;
            return created;
        }
        return Double.valueOf(doubles[pos]);
    }

    @TruffleBoundary
    private Object[] createBoxCache(int length) {
        Object[] cache = length <= BOX_CACHE_LIMIT ? new Object[length] : NO_BOX_CACHE;
        boxedValues = cache;
        return cache;
    }

    private void cacheBoxed(int pos, Object value) {
        Object[] boxed = boxedValues;
        if (boxed != null && pos < boxed.length) {
            boxed[pos] = value;
        }
    }

    private void invalidateBoxed(int pos) {
        Object[] boxed = boxedValues;
        if (boxed != null && pos < boxed.length) {
            boxed[pos] = null;
        }
    }

    // ---------------------------------------------------------------- internal pointer

    /** Key at the internal pointer, or null when the pointer is past either end. */
    @TruffleBoundary
    public Object pointerKey() {
        return pointer < 0 ? null : keyAt(pointer);
    }

    /** Value at the internal pointer, or null when the pointer is past either end. */
    @TruffleBoundary
    public Object pointerValue() {
        if (pointer < 0) {
            return null;
        }
        Object v = rawValueAt(pointer);
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    @TruffleBoundary
    public void pointerReset() {
        assert !immutable : "write to an immutable array";
        pointer = firstPos();
    }

    @TruffleBoundary
    public void pointerEnd() {
        assert !immutable : "write to an immutable array";
        pointer = lastPos();
    }

    /** Moves forward; a pointer already past either end stays there. */
    @TruffleBoundary
    public void pointerNext() {
        assert !immutable : "write to an immutable array";
        if (pointer >= 0) {
            pointer = nextPos(pointer);
        }
    }

    @TruffleBoundary
    public void pointerPrev() {
        assert !immutable : "write to an immutable array";
        if (pointer >= 0) {
            do {
                pointer--;
            } while (pointer >= 0 && keys != null && keys[pointer] == null);
        }
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "Array";
    }
}
