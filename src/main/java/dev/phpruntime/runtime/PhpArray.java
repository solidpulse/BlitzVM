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
    /** A null keys array means packed: values[0..size) have implicit integer keys. */
    private Object[] values = EMPTY_VALUES;
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
        result.values = Arrays.copyOf(values, values.length);
        for (int pos = 0; pos < used; pos++) {
            if (result.values[pos] instanceof PhpArray inner) {
                inner.share();
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

    @TruffleBoundary
    public Object getRaw(Object key) {
        int pos = find(normalizeKey(key));
        return pos < 0 ? null : values[pos];
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
        Object v = pos < 0 ? null : values[pos];
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

    @TruffleBoundary
    public PhpReference obtainElementRef(Object key) {
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
            Object v = values[pos];
            out.add(v instanceof PhpReference ref ? ref.getValue() : v);
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
        if (keys == null) {
            return Arrays.copyOf(values, size);
        }
        Object[] out = new Object[size];
        int i = 0;
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            out[i++] = values[pos];
        }
        return out;
    }

    @TruffleBoundary
    public List<Object> rawValues() {
        List<Object> out = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            out.add(values[pos]);
        }
        return out;
    }

    /** Snapshot of entries (unwrapped) for builtin callers. Foreach uses the cursor instead. */
    @TruffleBoundary
    public List<Map.Entry<Object, Object>> entries() {
        List<Map.Entry<Object, Object>> list = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            Object v = values[pos];
            list.add(Map.entry(keyAt(pos), v instanceof PhpReference ref ? ref.getValue() : v));
        }
        return list;
    }

    /** Snapshot of raw entries preserving {@link PhpReference} cells. */
    @TruffleBoundary
    public List<Map.Entry<Object, Object>> rawEntries() {
        List<Map.Entry<Object, Object>> list = new ArrayList<>(size);
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            list.add(Map.entry(keyAt(pos), values[pos]));
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
            Object v = values[i];
            out[i] = v instanceof PhpReference ref ? ref.getValue() : v;
        }
        return out;
    }

    /**
     * Iterates entries in array order without building a snapshot. Only for loops that cannot
     * modify this array and cannot run PHP, because a modification would move the cursor.
     */
    @TruffleBoundary
    public void forEachEntry(java.util.function.BiConsumer<Object, Object> action) {
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            Object v = values[pos];
            action.accept(keyAt(pos), v instanceof PhpReference ref ? ref.getValue() : v);
        }
    }

    /** True when an element is an object, so converting it to a string can run PHP. */
    @TruffleBoundary
    public boolean hasObjectValue() {
        for (int pos = firstPos(); pos >= 0; pos = nextPos(pos)) {
            Object v = values[pos];
            if (v instanceof PhpReference ref) {
                v = ref.getValue();
            }
            if (v instanceof PhpObject) {
                return true;
            }
        }
        return false;
    }

    /**
     * implode() over a snapshot of the values. The snapshot keeps a __toString() method that
     * modifies the array from changing what the loop still has to visit, which is php's
     * by-value behaviour. One boundary call covers the whole walk, and the builder is sized
     * from the element lengths to avoid growth copies.
     */
    @TruffleBoundary
    public String join(String glue) {
        if (size == 0) {
            return "";
        }
        Object[] snapshot = new Object[size];
        int n = 0;
        for (int pos = firstPos(); pos >= 0 && n < size; pos = nextPos(pos)) {
            Object v = values[pos];
            snapshot[n++] = v instanceof PhpReference ref ? ref.getValue() : v;
        }
        long chars = (long) glue.length() * (n - 1);
        for (Object v : snapshot) {
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

    @TruffleBoundary
    public Object removeLast() {
        if (size == 0) {
            return PhpNull.INSTANCE;
        }
        int pos = lastPos();
        Object lastKey = keyAt(pos);
        Object v = values[pos];
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
        Object v = values[pos];
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
        a.values = values.toArray();
        a.size = a.used = values.size();
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
        if (!replaceRef && values[pos] instanceof PhpReference ref && !(value instanceof PhpReference)) {
            ref.setValue(value);
        } else {
            values[pos] = value;
        }
    }

    @TruffleBoundary
    private void insertPacked(Object value) {
        assert !immutable : "write to an immutable array";
        if (size == values.length) {
            values = Arrays.copyOf(values, Math.max(8, size * 2));
        }
        values[size] = value;
        if (pointer < 0) {
            pointer = size;
        }
        used = ++size;
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
        values[pos] = null;
        size--;
        if (keys == null) {
            used = size;
        } else {
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

    /** Cursor positions are valid while the array is held as a shared, by-value snapshot. */
    @TruffleBoundary
    public int firstPos() {
        return nextPos(-1);
    }

    @TruffleBoundary
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

    @TruffleBoundary
    public Object keyAt(int pos) {
        return keys == null ? (long) pos : keys[pos];
    }

    /** Value at a cursor position, unwrapping a reference cell like {@link #entries()}. */
    @TruffleBoundary
    public Object valueAt(int pos) {
        Object v = values[pos];
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    @TruffleBoundary
    public Object rawValueAt(int pos) {
        return values[pos];
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
        Object v = values[pointer];
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
