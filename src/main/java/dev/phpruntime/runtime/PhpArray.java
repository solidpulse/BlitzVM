package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHP's ordered hash map. Keys are normalised to {@code Long} or {@code String}
 * exactly like PHP does ("5" becomes 5, true becomes 1, 1.7 becomes 1).
 *
 * <p>PHP arrays have value semantics. We implement that with copy-on-write: an
 * assignment calls {@link #share()} which bumps a share counter instead of copying;
 * the first write to a shared array makes a private copy. Writers must always use
 * the array returned by {@link #forWrite()}.
 */
public final class PhpArray implements TruffleObject {

    private LinkedHashMap<Object, Object> map;
    private long nextIndex;
    /** Number of extra owners. 0 means exclusively owned and safe to mutate in place. */
    private int shared;
    /** Internal pointer (reset/next/current) as a position in iteration order; valid when 0 <= pointer < size. */
    private int pointer;
    /** Keys in order, built on demand for the pointer functions; dropped on any structural change. */
    private List<Object> keyCache;

    public PhpArray() {
        this.map = new LinkedHashMap<>();
    }

    private PhpArray(LinkedHashMap<Object, Object> map, long nextIndex) {
        this.map = map;
        this.nextIndex = nextIndex;
    }

    /** Called on assignment; returns the same instance with an extra owner. */
    public PhpArray share() {
        shared++;
        return this;
    }

    /** Returns an instance safe to mutate: this one if unshared, otherwise a deep-enough copy. */
    @TruffleBoundary
    public PhpArray forWrite() {
        if (shared == 0) {
            return this;
        }
        shared--;
        LinkedHashMap<Object, Object> copy = new LinkedHashMap<>(map);
        for (Map.Entry<Object, Object> e : copy.entrySet()) {
            if (e.getValue() instanceof PhpArray inner) {
                e.setValue(inner.share());
            }
        }
        PhpArray result = new PhpArray(copy, nextIndex);
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
        throw new PhpError("Illegal offset type");
    }

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
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public Object get(Object key) {
        return map.get(normalizeKey(key));
    }

    @TruffleBoundary
    public boolean containsKey(Object key) {
        return map.containsKey(normalizeKey(key));
    }

    @TruffleBoundary
    public void put(Object key, Object value) {
        Object k = normalizeKey(key);
        if (map.put(k, value) == null && keyCache != null) {
            keyCache = null;
        }
        if (k instanceof Long l && l >= nextIndex) {
            nextIndex = l + 1;
        }
    }

    @TruffleBoundary
    public void append(Object value) {
        keyCache = null;
        map.put(nextIndex, value);
        nextIndex++;
    }

    @TruffleBoundary
    public void remove(Object key) {
        keyCache = null;
        map.remove(normalizeKey(key));
    }

    @TruffleBoundary
    public int size() {
        return map.size();
    }

    @TruffleBoundary
    public List<Object> keys() {
        return new ArrayList<>(map.keySet());
    }

    @TruffleBoundary
    public List<Object> values() {
        return new ArrayList<>(map.values());
    }

    /** Snapshot of entries, so foreach is unaffected by writes to the array inside the loop. */
    @TruffleBoundary
    public List<Map.Entry<Object, Object>> entries() {
        List<Map.Entry<Object, Object>> list = new ArrayList<>(map.size());
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            list.add(Map.entry(e.getKey(), e.getValue()));
        }
        return list;
    }

    @TruffleBoundary
    public Object removeLast() {
        if (map.isEmpty()) {
            return PhpNull.INSTANCE;
        }
        Object lastKey = null;
        Iterator<Object> it = map.keySet().iterator();
        while (it.hasNext()) {
            lastKey = it.next();
        }
        Object v = map.remove(lastKey);
        keyCache = null;
        pointer = 0;
        recomputeNextIndex();
        return v;
    }

    @TruffleBoundary
    public Object removeFirst() {
        if (map.isEmpty()) {
            return PhpNull.INSTANCE;
        }
        Object firstKey = map.keySet().iterator().next();
        Object v = map.remove(firstKey);
        reindex();
        pointer = 0;
        return v;
    }

    /** Renumber integer keys from 0, keeping string keys (used by sort, array_shift...). */
    @TruffleBoundary
    public void reindex() {
        LinkedHashMap<Object, Object> fresh = new LinkedHashMap<>();
        long i = 0;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            if (e.getKey() instanceof Long) {
                fresh.put(i++, e.getValue());
            } else {
                fresh.put(e.getKey(), e.getValue());
            }
        }
        map = fresh;
        keyCache = null;
        nextIndex = i;
    }

    private void recomputeNextIndex() {
        long max = -1;
        for (Object k : map.keySet()) {
            if (k instanceof Long l && l > max) {
                max = l;
            }
        }
        nextIndex = max + 1;
    }

    @TruffleBoundary
    public boolean isList() {
        long i = 0;
        for (Object k : map.keySet()) {
            if (!(k instanceof Long l) || l != i++) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static PhpArray ofList(List<?> values) {
        PhpArray a = new PhpArray();
        for (Object v : values) {
            a.append(v);
        }
        return a;
    }

    // ---------------------------------------------------------------- internal pointer

    private List<Object> orderedKeys() {
        if (keyCache == null) {
            keyCache = new ArrayList<>(map.keySet());
        }
        return keyCache;
    }

    /** Key at the internal pointer, or null when the pointer is past either end. */
    @TruffleBoundary
    public Object pointerKey() {
        List<Object> keys = orderedKeys();
        return pointer >= 0 && pointer < keys.size() ? keys.get(pointer) : null;
    }

    /** Value at the internal pointer, or null when the pointer is past either end. */
    @TruffleBoundary
    public Object pointerValue() {
        Object k = pointerKey();
        return k == null ? null : map.get(k);
    }

    @TruffleBoundary
    public void pointerReset() {
        pointer = 0;
    }

    @TruffleBoundary
    public void pointerEnd() {
        pointer = map.size() - 1;
    }

    /** Moves forward; a pointer already past either end stays there. */
    @TruffleBoundary
    public void pointerNext() {
        if (pointer >= 0 && pointer < map.size()) {
            pointer++;
        }
    }

    @TruffleBoundary
    public void pointerPrev() {
        if (pointer >= 0 && pointer < map.size()) {
            pointer--;
        }
    }

    @Override
    public String toString() {
        return "Array";
    }
}
