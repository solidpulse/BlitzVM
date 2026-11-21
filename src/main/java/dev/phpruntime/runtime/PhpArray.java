package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives;
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

    /**
     * Normalises a key the way php does, inline for the common cases. A string can only become
     * an integer when its first byte is a digit or {@code -} (php's ZEND_HANDLE_NUMERIC_STR
     * check), so everything else stays a string without the canonical-int scan.
     */
    public static Object normalizeKey(Object key) {
        if (key instanceof Long) {
            return key;
        }
        if (key instanceof String s) {
            if (s.isEmpty()) {
                return s;
            }
            char c = s.charAt(0);
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY,
                    c == '-' || (c >= '0' && c <= '9'))) {
                return normalizeNumericString(s);
            }
            return s;
        }
        return normalizeOtherKey(key);
    }

    /** A string that may be a canonical integer: "5" becomes 5, "05" and "-0" stay strings. */
    @TruffleBoundary
    private static Object normalizeNumericString(String s) {
        if (isCanonicalInt(s)) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return s;
            }
        }
        return s;
    }

    @TruffleBoundary
    private static Object normalizeOtherKey(Object key) {
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

    /**
     * Read by any key, normalising inline. The packed hit and the hash probe stay in compiled
     * code; only a possible numeric string goes through a boundary, in {@link #normalizeKey}.
     */
    public Object getFast(Object key) {
        return valueAtPos(findFast(normalizeKey(key)));
    }

    /** {@link #get(Object)} for a key already normalised by the caller. */
    public Object getNormalized(Object key) {
        return valueAtPos(findFast(key));
    }

    /** {@link #get(Object)} for a key whose normalised form and hash are already known. */
    public Object getKnown(KnownKey key) {
        return valueAtPos(findKnown(key.key(), key.hash()));
    }

    /** Raw element (a {@link PhpReference} cell is not unwrapped) by any key. */
    public Object getRawFast(Object key) {
        return rawAtPos(findFast(normalizeKey(key)));
    }

    public Object getRawNormalized(Object key) {
        return rawAtPos(findFast(key));
    }

    public Object getRawKnown(KnownKey key) {
        return rawAtPos(findKnown(key.key(), key.hash()));
    }

    public boolean containsKeyFast(Object key) {
        return findFast(normalizeKey(key)) >= 0;
    }

    public boolean containsKeyKnown(KnownKey key) {
        return findKnown(key.key(), key.hash()) >= 0;
    }

    private Object valueAtPos(int pos) {
        if (pos < 0) {
            return null;
        }
        Object v = values[pos];
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

    private Object rawAtPos(int pos) {
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
    public Object get(KnownKey key) {
        return getKnown(key);
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
     * A write of any key to an array the caller already made writable with {@link #forWrite()}.
     * Replacing a slot, appending into packed spare capacity and inserting into a hash that has
     * room stay inline; growing, conversion to a hash and unusual keys call {@link #store}.
     */
    public void putFast(Object key, Object value) {
        putNormalized(normalizeKey(key), value);
    }

    /** {@link #putFast} for a key already normalised by the caller. */
    public void putNormalized(Object key, Object value) {
        if (keys == null) {
            putPacked(key, value);
        } else {
            putHash(key, hash(key), value);
        }
    }

    /** {@link #putFast} for a key whose normalised form and hash are already known. */
    public void putKnown(KnownKey key, Object value) {
        if (keys == null) {
            putPacked(key.key(), value);
        } else {
            putHash(key.key(), key.hash(), value);
        }
    }

    private void putPacked(Object key, Object value) {
        assert !immutable && shared == 0 : "write to a shared array";
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY,
                !(key instanceof Long l && l >= 0 && l <= size))) {
            store(key, value, false);
            return;
        }
        long k = ((Long) key).longValue();
        int pos = (int) k;
        if (pos == size) {
            insertPackedInline(value);
        } else {
            replaceInline(pos, value, false);
        }
        updateNextIndex(k);
    }

    private void putHash(Object key, int hash, Object value) {
        assert !immutable && shared == 0 : "write to a shared array";
        int mask = index.length - 1;
        int bucket = hash & mask;
        int deletedBucket = -1;
        while (index[bucket] != 0) {
            int pos = index[bucket] - 1;
            if (pos >= 0 && hashes[pos] == hash && keyMatches(key, keys[pos])) {
                replaceInline(pos, value, false);
                if (key instanceof Long l) {
                    updateNextIndex(l);
                }
                return;
            }
            if (pos < 0 && deletedBucket < 0) {
                deletedBucket = bucket;
            }
            bucket = (bucket + 1) & mask;
        }
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY,
                used == values.length || indexUsed >= index.length * 3 / 4)) {
            store(key, value, false);
            return;
        }
        if (deletedBucket >= 0) {
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

    private static boolean keyMatches(Object key, Object stored) {
        if (stored == key) {
            return true;
        }
        if (key instanceof Long a) {
            return stored instanceof Long b && a.longValue() == b.longValue();
        }
        if (key instanceof String s) {
            return stored instanceof String t && s.equals(t);
        }
        return false;
    }

    private void replaceInline(int pos, Object value, boolean replaceRef) {
        assert !immutable && shared == 0 : "write to a shared array";
        if (values[pos] instanceof PhpReference) {
            // Assigning through a reference cell is the uncommon case; keep its rule in one place.
            replace(pos, value, replaceRef);
            return;
        }
        values[pos] = value;
    }

    private void insertPackedInline(Object value) {
        assert !immutable && shared == 0 : "write to a shared array";
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY,
                size == values.length)) {
            insertPacked(value);
            return;
        }
        values[size] = value;
        if (pointer < 0) {
            pointer = size;
        }
        used = ++size;
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

    /**
     * {@code $a[] = v} on an array the caller already made writable. Appending into packed spare
     * capacity is inline; growing or a next index that is not the packed end uses {@link #append}.
     */
    public void appendWritable(Object value) {
        if (keys == null && nextIndex == size) {
            insertPackedInline(value);
            updateNextIndex(size - 1L);
            return;
        }
        append(value);
    }

    @TruffleBoundary
    public void remove(Object key) {
        int pos = find(normalizeKey(key));
        if (pos >= 0) {
            delete(pos);
        }
    }

    /** {@link #remove} for a key already normalised, on an array the caller made writable. */
    public void removeNormalized(Object key) {
        int pos = keys == null ? packedPos(key) : findKnown(key, hash(key));
        if (pos >= 0) {
            delete(pos);
        }
    }

    public void removeKnown(KnownKey key) {
        int pos = findKnown(key.key(), key.hash());
        if (pos >= 0) {
            delete(pos);
        }
    }

    /** {@link #remove} for any key, normalising inline. */
    public void removeFast(Object key) {
        removeNormalized(normalizeKey(key));
    }

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

    /** A read by a normalised key: the packed bounds check without hashing, else the probe. */
    private int findFast(Object key) {
        if (keys == null) {
            return packedPos(key);
        }
        return findKnown(key, hash(key));
    }

    /**
     * {@link #find(Object)} with the hash already computed, inline for compiled callers. The
     * hash probe compares the stored hash first and identity before {@code equals} (literal
     * keys are interned); deleted slots are skipped.
     */
    private int findKnown(Object key, int hash) {
        if (keys == null) {
            return packedPos(key);
        }
        int mask = index.length - 1;
        int bucket = hash & mask;
        while (index[bucket] != 0) {
            int pos = index[bucket] - 1;
            if (pos >= 0 && hashes[pos] == hash) {
                Object stored = keys[pos];
                if (keyMatches(key, stored)) {
                    return pos;
                }
            }
            bucket = (bucket + 1) & mask;
        }
        return -1;
    }

    private int packedPos(Object key) {
        return key instanceof Long l && l >= 0 && l < size ? (int) l.longValue() : -1;
    }

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

    private void updateNextIndex(long key) {
        if (!nextIndexInitialized || key >= nextIndex) {
            nextIndex = key == Long.MAX_VALUE ? key : key + 1;
            nextIndexInitialized = true;
        }
    }

    private static int hash(Object key) {
        int h;
        if (key instanceof Long l) {
            long v = l.longValue();
            h = (int) (v ^ (v >>> 32));
        } else {
            h = key.hashCode();
        }
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
    public int firstPos() {
        return nextPos(-1);
    }

    public int nextPos(int pos) {
        int next = pos + 1;
        if (keys == null) {
            return next < used ? next : -1;
        }
        for (; next < used; next++) {
            if (keys[next] != null) {
                return next;
            }
        }
        return -1;
    }

    public int lastPos() {
        return used - 1;
    }

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
        Object v = values[pos];
        return v instanceof PhpReference ref ? ref.getValue() : v;
    }

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
