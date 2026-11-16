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

    public PhpArray() {
    }

    /** Called on assignment; returns the same instance with an extra owner. */
    @TruffleBoundary
    public PhpArray share() {
        shared++;
        return this;
    }

    /** Releases a temporary foreach owner; forWrite may already have consumed the last share. */
    @TruffleBoundary
    public void releaseShare() {
        if (shared > 0) {
            shared--;
        }
    }

    /** Returns an instance safe to mutate: this one if unshared, otherwise a deep-enough copy. */
    @TruffleBoundary
    public PhpArray forWrite() {
        if (shared == 0) {
            return this;
        }
        shared--;
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
            if (key instanceof Long l && l >= 0 && l < size) {
                return (int) l.longValue();
            }
            return -1;
        }
        int hash = hash(key);
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
        if (!replaceRef && values[pos] instanceof PhpReference ref && !(value instanceof PhpReference)) {
            ref.setValue(value);
        } else {
            values[pos] = value;
        }
    }

    @TruffleBoundary
    private void insertPacked(Object value) {
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
    private int lastPos() {
        return used - 1;
    }

    @TruffleBoundary
    public Object keyAt(int pos) {
        return keys == null ? (long) pos : keys[pos];
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
        pointer = firstPos();
    }

    @TruffleBoundary
    public void pointerEnd() {
        pointer = lastPos();
    }

    /** Moves forward; a pointer already past either end stays there. */
    @TruffleBoundary
    public void pointerNext() {
        if (pointer >= 0) {
            pointer = nextPos(pointer);
        }
    }

    @TruffleBoundary
    public void pointerPrev() {
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
