package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

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
        throw new PhpError(fn + "(): Argument #" + (i + 1) + " ($array) must be of type array, " + PhpOps.typeName(a[i]) + " given");
    }

    /** Writable copy of a by-reference array argument; undefined becomes an empty array. */
    private static PhpArray writable(Object[] a) {
        if (a[0] instanceof PhpArray x) {
            return x.share().forWrite();
        }
        if (a[0] == PhpNull.INSTANCE) {
            return new PhpArray();
        }
        throw new PhpError("Argument #1 ($array) must be of type array, " + PhpOps.typeName(a[0]) + " given");
    }

    static void register() {
        inspection();
        construction();
        mutation();
        sorting();
    }

    private static void inspection() {
        Builtins.def("count", 1, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpArray x)) {
                throw new PhpError("count(): Argument #1 ($value) must be of type Countable|array, " + PhpOps.typeName(a[0]) + " given");
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
                throw new PhpError("array_combine(): Argument #1 ($keys) and argument #2 ($values) must have the same number of elements");
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
                throw new PhpError("array_chunk(): Argument #2 ($length) must be greater than 0");
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
                throw new PhpError("range(): Argument #3 ($step) cannot be 0");
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
}
