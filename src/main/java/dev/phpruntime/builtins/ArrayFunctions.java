package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Array functions. Functions taking the array by reference (sort, array_push...) are
 * registered with a ref mask: they receive the current value, must not mutate it in
 * place (it may be shared copy-on-write) and store the new array back into args[0].
 */
final class ArrayFunctions {

    private static final int REF_FIRST = 1;

    private ArrayFunctions() {
    }

    private static PhpArray arr(Object[] a, int i, String fn) {
        if (a[i] instanceof PhpArray x) {
            return x;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #" + (i + 1) + " ($array) must be of type array, " + PhpOps.typeName(a[i]) + " given", null);
    }

    /** Writable copy of a by-reference array argument; undefined becomes an empty array. */
    private static PhpArray writable(Object[] a) {
        if (a[0] instanceof PhpArray x) {
            return x.share().forWrite();
        }
        if (a[0] == PhpNull.INSTANCE) {
            return new PhpArray();
        }
        throw new PhpError(PhpError.TYPE_ERROR, "Argument #1 ($array) must be of type array, " + PhpOps.typeName(a[0]) + " given", null);
    }

    static void register() {
        inspection();
        construction();
        mutation();
        sorting();
        callbacks();
        setOperations();
    }

    private static void inspection() {
        Builtins.def("count", 1, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpArray x)) {
                throw new PhpError(PhpError.TYPE_ERROR, "count(): Argument #1 ($value) must be of type Countable|array, " + PhpOps.typeName(a[0]) + " given", null);
            }
            return a.length > 1 && PhpOps.toLong(a[1]) == 1 ? countRecursive(x) : (long) x.size();
        });
        Builtins.def("sizeof", 1, 2, (ctx, a) -> (long) arr(a, 0, "sizeof").size());
        Builtins.def("in_array", 2, 3, (ctx, a) -> search(a[0], arr(a, 1, "in_array"), a.length > 2 && PhpOps.toBool(a[2])) != null);
        Builtins.def("array_search", 2, 3, (ctx, a) -> {
            Object k = search(a[0], arr(a, 1, "array_search"), a.length > 2 && PhpOps.toBool(a[2]));
            return k == null ? false : k;
        });
        Builtins.def("array_key_exists", 2, 2, (ctx, a) -> arr(a, 1, "array_key_exists").containsKey(a[0]));
        Builtins.def("key_exists", 2, 2, (ctx, a) -> arr(a, 1, "key_exists").containsKey(a[0]));
        Builtins.def("array_key_first", 1, 1, (ctx, a) -> {
            List<Object> keys = arr(a, 0, "array_key_first").keys();
            return keys.isEmpty() ? PhpNull.INSTANCE : keys.get(0);
        });
        Builtins.def("array_key_last", 1, 1, (ctx, a) -> {
            List<Object> keys = arr(a, 0, "array_key_last").keys();
            return keys.isEmpty() ? PhpNull.INSTANCE : keys.get(keys.size() - 1);
        });
        Builtins.def("array_is_list", 1, 1, (ctx, a) -> arr(a, 0, "array_is_list").isList());
        Builtins.def("array_sum", 1, 1, (ctx, a) -> {
            Object sum = 0L;
            for (Object v : arr(a, 0, "array_sum").values()) {
                sum = dev.phpruntime.runtime.PhpArith.add(sum, v);
            }
            return sum;
        });
        Builtins.def("array_product", 1, 1, (ctx, a) -> {
            Object p = 1L;
            for (Object v : arr(a, 0, "array_product").values()) {
                p = dev.phpruntime.runtime.PhpArith.mul(p, v);
            }
            return p;
        });
        Builtins.def("array_count_values", 1, 1, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (Object v : arr(a, 0, "array_count_values").values()) {
                Object c = out.get(v);
                out.put(v, c == null ? 1L : (Long) c + 1);
            }
            return out;
        });
    }

    private static void construction() {
        Builtins.def("array_keys", 1, 1, (ctx, a) -> PhpArray.ofList(arr(a, 0, "array_keys").keys()));
        Builtins.def("array_values", 1, 1, (ctx, a) -> PhpArray.ofList(shared(arr(a, 0, "array_values").values())));
        Builtins.def("array_merge", 0, -1, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (int i = 0; i < a.length; i++) {
                for (Map.Entry<Object, Object> e : arr(a, i, "array_merge").entries()) {
                    Object v = PhpOps.copyOnAssign(e.getValue());
                    if (e.getKey() instanceof Long) {
                        out.append(v);
                    } else {
                        out.put(e.getKey(), v);
                    }
                }
            }
            return out;
        });
        Builtins.def("array_combine", 2, 2, (ctx, a) -> {
            List<Object> keys = arr(a, 0, "array_combine").values();
            List<Object> values = arr(a, 1, "array_combine").values();
            if (keys.size() != values.size()) {
                throw new PhpError(PhpError.VALUE_ERROR, "array_combine(): Argument #1 ($keys) and argument #2 ($values) must have the same number of elements", null);
            }
            PhpArray out = new PhpArray();
            for (int i = 0; i < keys.size(); i++) {
                out.put(PhpOps.toStr(keys.get(i)), PhpOps.copyOnAssign(values.get(i)));
            }
            return out;
        });
        Builtins.def("array_flip", 1, 1, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : arr(a, 0, "array_flip").entries()) {
                out.put(e.getValue(), e.getKey());
            }
            return out;
        });
        Builtins.def("array_reverse", 1, 2, (ctx, a) -> {
            boolean preserve = a.length > 1 && PhpOps.toBool(a[1]);
            List<Map.Entry<Object, Object>> entries = arr(a, 0, "array_reverse").entries();
            Collections.reverse(entries);
            return fromEntries(entries, preserve);
        });
        Builtins.def("array_slice", 2, 4, (ctx, a) -> {
            List<Map.Entry<Object, Object>> entries = arr(a, 0, "array_slice").entries();
            int n = entries.size();
            long off = PhpOps.toLong(a[1]);
            int start = (int) (off < 0 ? Math.max(0, n + off) : Math.min(off, n));
            int end = n;
            if (a.length > 2 && a[2] != PhpNull.INSTANCE) {
                long len = PhpOps.toLong(a[2]);
                end = (int) (len < 0 ? Math.max(start, n + len) : Math.min(n, start + len));
            }
            return fromEntries(entries.subList(start, Math.max(start, end)), a.length > 3 && PhpOps.toBool(a[3]));
        });
        Builtins.def("array_unique", 1, 2, (ctx, a) -> {
            PhpArray out = new PhpArray();
            List<Object> seen = new ArrayList<>();
            for (Map.Entry<Object, Object> e : arr(a, 0, "array_unique").entries()) {
                boolean dup = false;
                for (Object s : seen) {
                    if (PhpOps.looseEquals(s, e.getValue())) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    seen.add(e.getValue());
                    out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
                }
            }
            return out;
        });
        Builtins.def("range", 2, 3, (ctx, a) -> range(a));
        Builtins.def("array_fill", 3, 3, (ctx, a) -> {
            PhpArray out = new PhpArray();
            long start = PhpOps.toLong(a[0]);
            for (long i = 0; i < PhpOps.toLong(a[1]); i++) {
                out.put(start + i, PhpOps.copyOnAssign(a[2]));
            }
            return out;
        });
        Builtins.def("array_fill_keys", 2, 2, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (Object k : arr(a, 0, "array_fill_keys").values()) {
                out.put(k, PhpOps.copyOnAssign(a[1]));
            }
            return out;
        });
        Builtins.def("array_pad", 3, 3, (ctx, a) -> {
            List<Object> values = shared(arr(a, 0, "array_pad").values());
            long size = PhpOps.toLong(a[1]);
            int missing = (int) (Math.abs(size) - values.size());
            List<Object> pad = new ArrayList<>(Collections.nCopies(Math.max(0, missing), a[2]));
            if (size < 0) {
                pad.addAll(values);
                return PhpArray.ofList(pad);
            }
            values = new ArrayList<>(values);
            values.addAll(pad);
            return PhpArray.ofList(values);
        });
        Builtins.def("array_chunk", 2, 3, (ctx, a) -> {
            int size = (int) PhpOps.toLong(a[1]);
            if (size < 1) {
                throw new PhpError(PhpError.VALUE_ERROR, "array_chunk(): Argument #2 ($length) must be greater than 0", null);
            }
            boolean preserve = a.length > 2 && PhpOps.toBool(a[2]);
            PhpArray out = new PhpArray();
            List<Map.Entry<Object, Object>> entries = arr(a, 0, "array_chunk").entries();
            for (int i = 0; i < entries.size(); i += size) {
                out.append(fromEntries(entries.subList(i, Math.min(entries.size(), i + size)), preserve));
            }
            return out;
        });
        Builtins.def("array_column", 2, 3, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (Object row : arr(a, 0, "array_column").values()) {
                if (!(row instanceof PhpArray r)) {
                    continue;
                }
                Object v = a[1] == PhpNull.INSTANCE ? row : r.get(a[1]);
                if (v == null) {
                    continue;
                }
                Object key = a.length > 2 ? r.get(a[2]) : null;
                if (key == null) {
                    out.append(PhpOps.copyOnAssign(v));
                } else {
                    out.put(key, PhpOps.copyOnAssign(v));
                }
            }
            return out;
        });
    }

    private static void mutation() {
        Builtins.defRef("array_push", 1, -1, REF_FIRST, (ctx, a) -> {
            PhpArray x = writable(a);
            for (int i = 1; i < a.length; i++) {
                x.append(PhpOps.copyOnAssign(a[i]));
            }
            a[0] = x;
            return (long) x.size();
        });
        Builtins.defRef("array_pop", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray x = writable(a);
            Object v = x.removeLast();
            a[0] = x;
            return v;
        });
        Builtins.defRef("array_shift", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray x = writable(a);
            Object v = x.removeFirst();
            a[0] = x;
            return v;
        });
        Builtins.defRef("array_unshift", 1, -1, REF_FIRST, (ctx, a) -> {
            PhpArray x = writable(a);
            PhpArray out = new PhpArray();
            for (int i = 1; i < a.length; i++) {
                out.append(PhpOps.copyOnAssign(a[i]));
            }
            for (Map.Entry<Object, Object> e : x.entries()) {
                if (e.getKey() instanceof Long) {
                    out.append(e.getValue());
                } else {
                    out.put(e.getKey(), e.getValue());
                }
            }
            a[0] = out;
            return (long) out.size();
        });
    }

    private static void sorting() {
        Comparator<Map.Entry<Object, Object>> byValue = (x, y) -> PhpOps.compare(x.getValue(), y.getValue());
        Comparator<Map.Entry<Object, Object>> byKey = (x, y) -> PhpOps.compare(x.getKey(), y.getKey());
        Builtins.defRef("sort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, byValue, false));
        Builtins.defRef("rsort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, byValue.reversed(), false));
        Builtins.defRef("asort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, byValue, true));
        Builtins.defRef("arsort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, byValue.reversed(), true));
        Builtins.defRef("ksort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, byKey, true));
        Builtins.defRef("krsort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, byKey.reversed(), true));
        Builtins.defRef("shuffle", 1, 1, REF_FIRST, (ctx, a) -> {
            List<Object> values = new ArrayList<>(writable(a).values());
            Collections.shuffle(values, java.util.concurrent.ThreadLocalRandom.current());
            a[0] = PhpArray.ofList(values);
            return true;
        });
    }

    private static Object sort(Object[] a, Comparator<Map.Entry<Object, Object>> cmp, boolean keepKeys) {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(writable(a).entries());
        entries.sort(cmp); // stable, like PHP 8's sort
        a[0] = fromEntries(entries, keepKeys);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static Object search(Object needle, PhpArray hay, boolean strict) {
        for (Map.Entry<Object, Object> e : hay.entries()) {
            if (strict ? PhpOps.strictEquals(needle, e.getValue()) : PhpOps.looseEquals(needle, e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    private static long countRecursive(PhpArray x) {
        long n = 0;
        for (Object v : x.values()) {
            n++;
            if (v instanceof PhpArray inner) {
                n += countRecursive(inner);
            }
        }
        return n;
    }

    private static List<Object> shared(List<Object> values) {
        values.replaceAll(PhpOps::copyOnAssign);
        return values;
    }

    /** Builds an array from entries; integer keys are renumbered unless preserveKeys. */
    private static PhpArray fromEntries(List<Map.Entry<Object, Object>> entries, boolean preserveKeys) {
        PhpArray out = new PhpArray();
        for (Map.Entry<Object, Object> e : entries) {
            Object v = PhpOps.copyOnAssign(e.getValue());
            if (!preserveKeys && e.getKey() instanceof Long) {
                out.append(v);
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private static PhpArray range(Object[] a) {
        Object start = a[0];
        Object end = a[1];
        PhpArray out = new PhpArray();
        if (start instanceof String s && end instanceof String e && s.length() == 1 && e.length() == 1
                && !Character.isDigit(s.charAt(0)) && !Character.isDigit(e.charAt(0))) {
            int step = a.length > 2 ? (int) Math.abs(PhpOps.toLong(a[2])) : 1;
            char from = s.charAt(0);
            char to = e.charAt(0);
            if (from <= to) {
                for (char c = from; c <= to; c += (char) step) {
                    out.append(String.valueOf(c));
                }
            } else {
                for (char c = from; c >= to; c -= (char) step) {
                    out.append(String.valueOf(c));
                }
            }
            return out;
        }
        Object from = PhpOps.toNumber(start);
        Object to = PhpOps.toNumber(end);
        Object stepObj = a.length > 2 ? PhpOps.toNumber(a[2]) : 1L;
        boolean useDouble = from instanceof Double || to instanceof Double || stepObj instanceof Double;
        if (useDouble) {
            double f = PhpOps.toDouble(from);
            double t = PhpOps.toDouble(to);
            double step = Math.abs(PhpOps.toDouble(stepObj));
            if (step == 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "range(): Argument #3 ($step) cannot be 0", null);
            }
            long n = (long) Math.floor(Math.abs(t - f) / step + 1e-9);
            for (long i = 0; i <= n; i++) {
                out.append(f <= t ? f + i * step : f - i * step);
            }
            return out;
        }
        long f = (Long) from;
        long t = (Long) to;
        long step = Math.abs((Long) stepObj);
        if (step == 0) {
            throw new PhpError("range(): Argument #3 ($step) cannot be 0");
        }
        if (f <= t) {
            for (long i = f; i <= t; i += step) {
                out.append(i);
            }
        } else {
            for (long i = f; i >= t; i -= step) {
                out.append(i);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ set operations, replace, splice

    private static void setOperations() {
        Builtins.def("array_diff", 1, -1, (ctx, a) -> filterAgainst(a, "array_diff", false, true, false));
        Builtins.def("array_diff_key", 1, -1, (ctx, a) -> filterAgainst(a, "array_diff_key", true, false, false));
        Builtins.def("array_diff_assoc", 1, -1, (ctx, a) -> filterAgainst(a, "array_diff_assoc", true, true, false));
        Builtins.def("array_intersect", 1, -1, (ctx, a) -> filterAgainst(a, "array_intersect", false, true, true));
        Builtins.def("array_intersect_key", 1, -1, (ctx, a) -> filterAgainst(a, "array_intersect_key", true, false, true));
        Builtins.def("array_intersect_assoc", 1, -1, (ctx, a) -> filterAgainst(a, "array_intersect_assoc", true, true, true));
        Builtins.def("array_replace", 1, -1, (ctx, a) -> {
            PhpArray out = arr(a, 0, "array_replace").share().forWrite();
            for (int i = 1; i < a.length; i++) {
                for (Map.Entry<Object, Object> e : arr(a, i, "array_replace").entries()) {
                    out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
                }
            }
            return out;
        });
        Builtins.def("array_replace_recursive", 1, -1, (ctx, a) -> {
            PhpArray out = arr(a, 0, "array_replace_recursive");
            for (int i = 1; i < a.length; i++) {
                out = replaceRecursive(out, arr(a, i, "array_replace_recursive"));
            }
            return out;
        });
        Builtins.def("array_merge_recursive", 0, -1, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (int i = 0; i < a.length; i++) {
                mergeRecursive(out, arr(a, i, "array_merge_recursive"));
            }
            return out;
        });
        Builtins.def("array_change_key_case", 1, 2, (ctx, a) -> {
            boolean upper = a.length > 1 && PhpOps.toLong(a[1]) == 1;
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : arr(a, 0, "array_change_key_case").entries()) {
                Object k = e.getKey() instanceof String s ? (upper ? s.toUpperCase() : s.toLowerCase()) : e.getKey();
                out.put(k, PhpOps.copyOnAssign(e.getValue()));
            }
            return out;
        });
        Builtins.def("array_first", 1, 1, (ctx, a) -> {
            List<Object> values = arr(a, 0, "array_first").values();
            return values.isEmpty() ? PhpNull.INSTANCE : PhpOps.copyOnAssign(values.get(0));
        });
        Builtins.def("array_last", 1, 1, (ctx, a) -> {
            List<Object> values = arr(a, 0, "array_last").values();
            return values.isEmpty() ? PhpNull.INSTANCE : PhpOps.copyOnAssign(values.get(values.size() - 1));
        });
        // TODO(classes): objects implementing Countable are countable too.
        Builtins.def("is_countable", 1, 1, (ctx, a) -> a[0] instanceof PhpArray);
        Builtins.defRef("array_splice", 2, 4, REF_FIRST, (ctx, a) -> splice(a));
    }

    /**
     * array_diff/array_intersect and their _key/_assoc forms. An entry of the first array is kept
     * when it appears in none (diff) or all (intersect) of the others, comparing keys and/or
     * values as strings, as PHP does.
     */
    private static PhpArray filterAgainst(Object[] a, String fn, boolean byKey, boolean byValue, boolean intersect) {
        PhpArray first = arr(a, 0, fn);
        List<java.util.Set<String>> valueSets = new ArrayList<>();
        List<PhpArray> others = new ArrayList<>();
        for (int i = 1; i < a.length; i++) {
            PhpArray other = arr(a, i, fn);
            others.add(other);
            if (byValue && !byKey) {
                java.util.Set<String> set = new java.util.HashSet<>();
                for (Object v : other.values()) {
                    set.add(PhpOps.toStr(v));
                }
                valueSets.add(set);
            }
        }
        PhpArray out = new PhpArray();
        for (Map.Entry<Object, Object> e : first.entries()) {
            boolean keep = true;
            for (int i = 0; i < others.size(); i++) {
                boolean found;
                if (byKey) {
                    Object v = others.get(i).get(e.getKey());
                    found = v != null && (!byValue || PhpOps.toStr(v).equals(PhpOps.toStr(e.getValue())));
                } else {
                    found = valueSets.get(i).contains(PhpOps.toStr(e.getValue()));
                }
                if (found != intersect) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
            }
        }
        return out;
    }

    private static PhpArray replaceRecursive(PhpArray base, PhpArray with) {
        PhpArray out = base.share().forWrite();
        for (Map.Entry<Object, Object> e : with.entries()) {
            Object existing = out.get(e.getKey());
            if (existing instanceof PhpArray x && e.getValue() instanceof PhpArray y) {
                out.put(e.getKey(), replaceRecursive(x, y));
            } else {
                out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
            }
        }
        return out;
    }

    /** php_array_merge_recursive: string keys present in both are merged into a list, int keys append. */
    private static void mergeRecursive(PhpArray dest, PhpArray src) {
        for (Map.Entry<Object, Object> e : src.entries()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (k instanceof String && dest.containsKey(k)) {
                Object existing = dest.get(k);
                PhpArray merged;
                if (existing instanceof PhpArray x) {
                    merged = x.share().forWrite();
                } else {
                    merged = new PhpArray();
                    merged.append(existing);
                }
                if (v instanceof PhpArray y) {
                    mergeRecursive(merged, y);
                } else {
                    merged.append(PhpOps.copyOnAssign(v));
                }
                dest.put(k, merged);
            } else if (k instanceof String) {
                dest.put(k, PhpOps.copyOnAssign(v));
            } else {
                dest.append(PhpOps.copyOnAssign(v));
            }
        }
    }

    private static Object splice(Object[] a) {
        PhpArray x = writable(a);
        List<Map.Entry<Object, Object>> entries = x.entries();
        int n = entries.size();
        long offset = PhpOps.toLong(a[1]);
        if (offset < 0) {
            offset = Math.max(0, n + offset);
        } else if (offset > n) {
            offset = n;
        }
        long end;
        if (a.length < 3 || a[2] == PhpNull.INSTANCE) {
            end = n;
        } else {
            long len = PhpOps.toLong(a[2]);
            end = len < 0 ? Math.max(offset, n + len) : Math.min(n, offset + len);
        }
        List<Object> replacement = new ArrayList<>();
        if (a.length > 3) {
            if (a[3] instanceof PhpArray r) {
                replacement.addAll(r.values());
            } else if (a[3] != PhpNull.INSTANCE) {
                replacement.add(a[3]);
            }
        }
        PhpArray removed = new PhpArray();
        PhpArray out = new PhpArray();
        for (int i = 0; i < n; i++) {
            if (i == offset) {
                for (Object v : replacement) {
                    out.append(PhpOps.copyOnAssign(v));
                }
            }
            Map.Entry<Object, Object> e = entries.get(i);
            if (i >= offset && i < end) {
                if (e.getKey() instanceof Long) {
                    removed.append(e.getValue());
                } else {
                    removed.put(e.getKey(), e.getValue());
                }
            } else if (e.getKey() instanceof Long) {
                out.append(e.getValue());
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        if (offset >= n) {
            for (Object v : replacement) {
                out.append(PhpOps.copyOnAssign(v));
            }
        }
        a[0] = out;
        return removed;
    }

    private static void callbacks() {
        Builtins.def("array_map", 2, -1, ArrayFunctions::arrayMap);
        Builtins.def("array_filter", 1, 3, ArrayFunctions::arrayFilter);
        Builtins.def("array_reduce", 2, 3, ArrayFunctions::arrayReduce);
        Builtins.defRef("array_walk", 2, 3, REF_FIRST, ArrayFunctions::arrayWalk);
        Builtins.defRef("array_walk_recursive", 2, 3, REF_FIRST, ArrayFunctions::arrayWalkRecursive);
        Builtins.defRef("usort", 2, 2, REF_FIRST, (ctx, a) -> callbackSort(ctx, a, false, false));
        Builtins.defRef("uasort", 2, 2, REF_FIRST, (ctx, a) -> callbackSort(ctx, a, true, false));
        Builtins.defRef("uksort", 2, 2, REF_FIRST, (ctx, a) -> callbackSort(ctx, a, true, true));
        Builtins.def("array_find", 2, 2, (ctx, a) -> findEntry(ctx, a, "array_find", false));
        Builtins.def("array_find_key", 2, 2, (ctx, a) -> findEntry(ctx, a, "array_find_key", true));
        Builtins.def("array_any", 2, 2, (ctx, a) -> anyOrAll(ctx, a, true));
        Builtins.def("array_all", 2, 2, (ctx, a) -> anyOrAll(ctx, a, false));
    }

    private static PhpArray arrayMap(PhpContext ctx, Object[] a) {
        PhpCallable cb = a[0] == PhpNull.INSTANCE ? null : PhpCallable.resolve(ctx, a[0], null);
        if (a.length == 2) {
            PhpArray src = arr(a, 1, "array_map");
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : src.entries()) {
                Object val = cb == null ? PhpOps.copyOnAssign(e.getValue()) : cb.call(ctx, null, null, e.getValue());
                out.put(e.getKey(), val);
            }
            return out;
        }
        int numArrays = a.length - 1;
        List<List<Object>> lists = new ArrayList<>(numArrays);
        int maxLen = 0;
        for (int i = 0; i < numArrays; i++) {
            List<Object> vals = arr(a, i + 1, "array_map").values();
            lists.add(vals);
            if (vals.size() > maxLen) {
                maxLen = vals.size();
            }
        }
        PhpArray out = new PhpArray();
        for (int idx = 0; idx < maxLen; idx++) {
            Object[] callArgs = new Object[numArrays];
            for (int j = 0; j < numArrays; j++) {
                List<Object> list = lists.get(j);
                callArgs[j] = idx < list.size() ? list.get(idx) : PhpNull.INSTANCE;
            }
            if (cb == null) {
                PhpArray row = new PhpArray();
                for (Object v : callArgs) {
                    row.append(PhpOps.copyOnAssign(v));
                }
                out.append(row);
            } else {
                out.append(cb.call(ctx, null, null, callArgs));
            }
        }
        return out;
    }

    private static PhpArray arrayFilter(PhpContext ctx, Object[] a) {
        PhpArray src = arr(a, 0, "array_filter");
        PhpCallable cb = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpCallable.resolve(ctx, a[1], null) : null;
        long mode = a.length > 2 ? PhpOps.toLong(a[2]) : 0L;
        PhpArray out = new PhpArray();
        for (Map.Entry<Object, Object> e : src.entries()) {
            boolean keep;
            if (cb == null) {
                keep = PhpOps.toBool(e.getValue());
            } else if (mode == 2L) { // ARRAY_FILTER_USE_KEY
                keep = PhpOps.toBool(cb.call(ctx, null, null, e.getKey()));
            } else if (mode == 1L) { // ARRAY_FILTER_USE_BOTH
                keep = PhpOps.toBool(cb.call(ctx, null, null, e.getValue(), e.getKey()));
            } else {
                keep = PhpOps.toBool(cb.call(ctx, null, null, e.getValue()));
            }
            if (keep) {
                out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
            }
        }
        return out;
    }

    private static Object arrayReduce(PhpContext ctx, Object[] a) {
        PhpArray src = arr(a, 0, "array_reduce");
        PhpCallable cb = PhpCallable.resolve(ctx, a[1], null);
        Object acc = a.length > 2 ? PhpOps.copyOnAssign(a[2]) : PhpNull.INSTANCE;
        for (Object v : src.values()) {
            acc = cb.call(ctx, null, null, acc, v);
        }
        return acc;
    }

    private static Object arrayWalk(PhpContext ctx, Object[] a) {
        PhpArray out = writable(a);
        PhpCallable cb = PhpCallable.resolve(ctx, a[1], ctx.currentBuiltinCallSite());
        boolean hasExtra = a.length > 2;
        Object extra = hasExtra ? a[2] : null;
        walkArrayInPlace(ctx, out, cb, hasExtra, extra, false);
        a[0] = out;
        return true;
    }

    private static Object arrayWalkRecursive(PhpContext ctx, Object[] a) {
        PhpArray out = writable(a);
        PhpCallable cb = PhpCallable.resolve(ctx, a[1], ctx.currentBuiltinCallSite());
        boolean hasExtra = a.length > 2;
        Object extra = hasExtra ? a[2] : null;
        walkArrayInPlace(ctx, out, cb, hasExtra, extra, true);
        a[0] = out;
        return true;
    }

    private static void walkArrayInPlace(PhpContext ctx, PhpArray arr, PhpCallable cb,
                                         boolean hasExtra, Object extra, boolean recursive) {
        boolean callbackWantsRef = cb.callTarget() != null
                && cb.callTarget().getRootNode() instanceof dev.phpruntime.nodes.PhpFunctionRootNode fn
                && fn.isParamByRef(0);
        for (Object k : arr.keys()) {
            if (!arr.containsKey(k)) {
                continue;
            }
            Object cur = arr.get(k);
            if (recursive && cur instanceof PhpArray inner) {
                PhpArray writableInner = inner.forWrite();
                arr.put(k, writableInner);
                walkArrayInPlace(ctx, writableInner, cb, hasExtra, extra, true);
                continue;
            }
            if (callbackWantsRef) {
                PhpReference elemRef = arr.obtainElementRef(k);
                if (hasExtra) {
                    cb.call(ctx, null, ctx.currentBuiltinCallSite(), elemRef, k, extra);
                } else {
                    cb.call(ctx, null, ctx.currentBuiltinCallSite(), elemRef, k);
                }
            } else {
                if (hasExtra) {
                    cb.call(ctx, null, ctx.currentBuiltinCallSite(), cur, k, extra);
                } else {
                    cb.call(ctx, null, ctx.currentBuiltinCallSite(), cur, k);
                }
            }
        }
    }

    private static Object callbackSort(PhpContext ctx, Object[] a, boolean keepKeys, boolean byKey) {
        PhpCallable cb = PhpCallable.resolve(ctx, a[1], ctx.currentBuiltinCallSite());
        boolean ref0 = !byKey && cb.callTarget() != null
                && cb.callTarget().getRootNode() instanceof dev.phpruntime.nodes.PhpFunctionRootNode fn0
                && fn0.isParamByRef(0);
        boolean ref1 = !byKey && cb.callTarget() != null
                && cb.callTarget().getRootNode() instanceof dev.phpruntime.nodes.PhpFunctionRootNode fn1
                && fn1.isParamByRef(1);
        PhpArray writableArr = writable(a);
        List<Map.Entry<Object, Object>> entries = new ArrayList<>();
        for (Object k : writableArr.keys()) {
            Object val = (ref0 || ref1) ? writableArr.obtainElementRef(k) : writableArr.get(k);
            entries.add(Map.entry(k, val));
        }
        entries.sort((e1, e2) -> {
            Object res;
            if (byKey) {
                Object k1 = ref0 ? new PhpReference(e1.getKey()) : e1.getKey();
                Object k2 = ref1 ? new PhpReference(e2.getKey()) : e2.getKey();
                res = cb.call(ctx, null, ctx.currentBuiltinCallSite(), k1, k2);
            } else {
                res = cb.call(ctx, null, ctx.currentBuiltinCallSite(), e1.getValue(), e2.getValue());
            }
            return PhpOps.compare(res, 0L);
        });
        if (keepKeys) {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : entries) {
                Object v = e.getValue() instanceof PhpReference ref ? ref.value : e.getValue();
                out.put(e.getKey(), PhpOps.copyOnAssign(v));
            }
            a[0] = out;
        } else {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : entries) {
                Object v = e.getValue() instanceof PhpReference ref ? ref.value : e.getValue();
                out.append(PhpOps.copyOnAssign(v));
            }
            a[0] = out;
        }
        return true;
    }

    private static Object findEntry(PhpContext ctx, Object[] a, String fn, boolean returnKey) {
        PhpArray src = arr(a, 0, fn);
        PhpCallable cb = PhpCallable.resolve(ctx, a[1], null);
        for (Map.Entry<Object, Object> e : src.entries()) {
            if (PhpOps.toBool(cb.call(ctx, null, null, e.getValue(), e.getKey()))) {
                return returnKey ? e.getKey() : PhpOps.copyOnAssign(e.getValue());
            }
        }
        return PhpNull.INSTANCE;
    }

    private static boolean anyOrAll(PhpContext ctx, Object[] a, boolean isAny) {
        PhpArray src = arr(a, 0, isAny ? "array_any" : "array_all");
        PhpCallable cb = PhpCallable.resolve(ctx, a[1], null);
        for (Map.Entry<Object, Object> e : src.entries()) {
            boolean matched = PhpOps.toBool(cb.call(ctx, null, null, e.getValue(), e.getKey()));
            if (isAny && matched) {
                return true;
            }
            if (!isAny && !matched) {
                return false;
            }
        }
        return !isAny;
    }
}
