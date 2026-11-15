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
        return new PhpArray(copy, nextIndex);
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
        map.put(k, value);
        if (k instanceof Long l && l >= nextIndex) {
            nextIndex = l + 1;
        }
    }

    @TruffleBoundary
    public void append(Object value) {
        map.put(nextIndex, value);
        nextIndex++;
    }

    @TruffleBoundary
    public void remove(Object key) {
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

    @Override
    public String toString() {
        return "Array";
    }
}
