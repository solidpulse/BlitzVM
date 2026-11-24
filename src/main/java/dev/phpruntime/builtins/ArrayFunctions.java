package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Array functions. Functions taking the array by reference (sort, array_push...) are
 * registered with a ref mask: they receive the current value and store the new array
 * back into args[0]. Unless they use {@link #writableInPlace}, they must not mutate the
 * value in place (it may be shared copy-on-write).
 */
final class ArrayFunctions {

    private static final int REF_FIRST = 1;

    private ArrayFunctions() {
    }

    private static PhpArray arr(Object[] a, int i, String fn) {
        if (a[i] instanceof PhpArray x) {
            return x;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #" + (i + 1) + " ($array) must be of type array, " + PhpOps.typeDescription(a[i]) + " given", null);
    }

    /** Forced copy for the sort family and any builtin that runs a user callback, which must never see a half-modified array. */
    private static PhpArray writable(Object[] a) {
        if (a[0] instanceof PhpArray x) {
            return x.share().forWrite();
        }
        if (a[0] == PhpNull.INSTANCE) {
            return new PhpArray();
        }
        throw new PhpError(PhpError.TYPE_ERROR, "Argument #1 ($array) must be of type array, " + PhpOps.typeName(a[0]) + " given", null);
    }

    /** In-place write for mutation-only builtins (push/pop/shift/unshift/splice) that run no PHP code; undefined becomes empty. */
    private static PhpArray writableInPlace(Object[] a) {
        if (a[0] instanceof PhpArray x) {
            return x.forWrite();
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
        Builtins.def("array_keys", 1, 3, (ctx, a) -> {
            PhpArray array = arr(a, 0, "array_keys");
            PhpArray out = new PhpArray();
            boolean filter = a.length > 1;
            boolean strict = a.length > 2 && PhpOps.toBool(a[2]);
            for (int pos = array.firstPos(); pos >= 0; pos = array.nextPos(pos)) {
                Object v = array.rawValueAt(pos);
                if (v instanceof PhpReference ref) {
                    v = ref.getValue();
                }
                if (filter && !(strict ? PhpOps.strictEquals(a[1], v) : PhpOps.looseEquals(a[1], v))) {
                    continue;
                }
                out.append(array.keyAt(pos));
            }
            return out;
        });
        Builtins.def("array_values", 1, 1, (ctx, a) -> PhpArray.ofList(shared(arr(a, 0, "array_values").values())));
        Builtins.def("array_merge", 0, -1, (ctx, a) -> {
            int total = 0;
            boolean stringKeys = false;
            for (int i = 0; i < a.length; i++) {
                PhpArray src = arr(a, i, "array_merge");
                total += src.size();
                if (!src.isList()) {
                    stringKeys = true;
                }
            }
            PhpArray out = PhpArray.withCapacity(total, stringKeys);
            for (int i = 0; i < a.length; i++) {
                arr(a, i, "array_merge").forEachEntry((key, value) -> putEntry(out, key, value, false));
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
            arr(a, 0, "array_flip").forEachEntry((key, value) -> out.put(value, key));
            return out;
        });
        Builtins.def("array_reverse", 1, 2, (ctx, a) -> {
            boolean preserve = a.length > 1 && PhpOps.toBool(a[1]);
            PhpArray src = arr(a, 0, "array_reverse");
            PhpArray out = new PhpArray();
            for (int pos = src.lastPos(); pos >= 0; pos = src.prevPos(pos)) {
                putEntry(out, src.keyAt(pos), src.valueAt(pos), preserve);
            }
            return out;
        });
        Builtins.def("array_slice", 2, 4, (ctx, a) -> {
            PhpArray src = arr(a, 0, "array_slice");
            int n = src.size();
            long off = PhpOps.toLong(a[1]);
            int start = (int) (off < 0 ? Math.max(0, n + off) : Math.min(off, n));
            int end = n;
            if (a.length > 2 && a[2] != PhpNull.INSTANCE) {
                long len = PhpOps.toLong(a[2]);
                end = (int) (len < 0 ? Math.max(start, n + len) : Math.min(n, start + len));
            }
            boolean preserve = a.length > 3 && PhpOps.toBool(a[3]);
            PhpArray out = new PhpArray();
            int i = 0;
            for (int pos = src.firstPos(); pos >= 0 && i < end; pos = src.nextPos(pos), i++) {
                if (i >= start) {
                    putEntry(out, src.keyAt(pos), src.valueAt(pos), preserve);
                }
            }
            return out;
        });
        Builtins.def("array_unique", 1, 2, (ctx, a) -> {
            PhpArray src = arr(a, 0, "array_unique");
            long flags = a.length > 1 ? PhpOps.toLong(a[1]) : 2; // SORT_STRING
            long base = flags & ~8L; // ~SORT_FLAG_CASE
            boolean foldCase = (flags & 8L) != 0;
            if (base == 2L || base == 5L) {
                return uniqueByString(src, base == 2L && foldCase);
            }
            boolean natural = base == 6L;
            boolean numeric = base == 1L;
            PhpArray out = new PhpArray();
            List<Object> seen = new ArrayList<>();
            PhpArray view = src.walkView();
            try {
                for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                    Object raw = view.rawValueAt(pos);
                    Object value = unwrap(raw);
                    boolean dup = false;
                    for (Object s : seen) {
                        boolean equal = natural
                                ? StringFunctions.natCompare(PhpOps.toStr(s), PhpOps.toStr(value), foldCase) == 0
                                : numeric ? numericEquals(s, value) : PhpOps.looseEquals(s, value);
                        if (equal) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        seen.add(value);
                        out.put(view.keyAt(pos), PhpOps.copyOnAssign(raw));
                    }
                }
            } finally {
                view.releaseShare();
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
            int length = (int) PhpOps.toLong(a[1]);
            if (length < 1) {
                throw new PhpError(PhpError.VALUE_ERROR, "array_chunk(): Argument #2 ($length) must be greater than 0", null);
            }
            boolean preserve = a.length > 2 && PhpOps.toBool(a[2]);
            PhpArray src = arr(a, 0, "array_chunk");
            PhpArray out = new PhpArray();
            PhpArray chunk = null;
            int count = 0;
            for (int pos = src.firstPos(); pos >= 0; pos = src.nextPos(pos)) {
                if (count == 0) {
                    chunk = new PhpArray();
                }
                putEntry(chunk, src.keyAt(pos), src.valueAt(pos), preserve);
                if (++count == length) {
                    out.append(chunk);
                    count = 0;
                    chunk = null;
                }
            }
            if (count > 0) {
                out.append(chunk);
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
            PhpArray x = writableInPlace(a);
            for (int i = 1; i < a.length; i++) {
                x.append(PhpOps.copyOnAssign(a[i]));
            }
            a[0] = x;
            return (long) x.size();
        });
        Builtins.defRef("array_pop", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray x = writableInPlace(a);
            Object v = x.removeLast();
            a[0] = x;
            return v;
        });
        Builtins.defRef("array_shift", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray x = writableInPlace(a);
            Object v = x.removeFirst();
            a[0] = x;
            return v;
        });
        Builtins.defRef("array_unshift", 1, -1, REF_FIRST, (ctx, a) -> {
            PhpArray x = writableInPlace(a);
            PhpArray out = new PhpArray();
            for (int i = 1; i < a.length; i++) {
                out.append(PhpOps.copyOnAssign(a[i]));
            }
            x.forEachEntry((key, value) -> {
                if (key instanceof Long) {
                    out.append(value);
                } else {
                    out.put(key, value);
                }
            });
            a[0] = out;
            return (long) out.size();
        });
    }

    private static void sorting() {
        Builtins.defRef("sort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, sortComparator(a, false), false));
        Builtins.defRef("rsort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, sortComparator(a, false).reversed(), false));
        Builtins.defRef("asort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, sortComparator(a, false), true));
        Builtins.defRef("arsort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, sortComparator(a, false).reversed(), true));
        Builtins.defRef("ksort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, sortComparator(a, true), true));
        Builtins.defRef("krsort", 1, 2, REF_FIRST, (ctx, a) -> sort(a, sortComparator(a, true).reversed(), true));
        Builtins.defRef("natsort", 1, 1, REF_FIRST, (ctx, a) -> sort(a,
                (x, y) -> StringFunctions.natCompare(PhpOps.toStr(x.getValue()), PhpOps.toStr(y.getValue()), false), true));
        Builtins.defRef("natcasesort", 1, 1, REF_FIRST, (ctx, a) -> sort(a,
                (x, y) -> StringFunctions.natCompare(PhpOps.toStr(x.getValue()), PhpOps.toStr(y.getValue()), true), true));
        Builtins.defRef("array_multisort", 1, -1, 0x7FFFFFFF, (ctx, a) -> arrayMultisort(a));
        Builtins.defRef("shuffle", 1, 1, REF_FIRST, (ctx, a) -> {
            List<Object> values = new ArrayList<>(writable(a).values());
            RandomFunctions.shuffleValues(ctx, values);
            a[0] = PhpArray.ofList(values);
            return true;
        });
    }

    /**
     * array_multisort(): sorts every listed array by the columns in order. The reference
     * implementation keeps the arrays at their argument positions, so the writeback of the
     * all-reference argument list replaces each one.
     */
    private static Object arrayMultisort(Object[] a) {
        List<PhpArray> arrays = new ArrayList<>();
        List<Long> orders = new ArrayList<>();
        List<Long> types = new ArrayList<>();
        long order = 4; // SORT_ASC
        long type = 0; // SORT_REGULAR
        for (int i = 0; i < a.length; i++) {
            if (a[i] instanceof PhpArray array) {
                arrays.add(array);
                orders.add(order);
                types.add(type);
                order = 4;
                type = 0;
            } else if (a[i] instanceof Long value) {
                long flag = value;
                if (flag == 4 || flag == 3) {
                    if (arrays.isEmpty()) {
                        order = flag;
                    } else {
                        orders.set(orders.size() - 1, flag);
                    }
                } else if (flag == 0 || flag == 1 || flag == 2 || flag == 5 || flag == 6
                        || ((flag & 8) != 0 && ((flag & ~8) == 2 || (flag & ~8) == 6))) {
                    if (arrays.isEmpty()) {
                        type = flag;
                    } else {
                        types.set(types.size() - 1, flag);
                    }
                } else {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "array_multisort(): Argument #" + (i + 1) + " must be a valid sort flag", null);
                }
            } else {
                throw new PhpError(PhpError.TYPE_ERROR, "array_multisort(): Argument #" + (i + 1)
                        + " ($array) must be an array or a sort flag that has not already been specified", null);
            }
        }
        if (arrays.isEmpty()) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "array_multisort(): Argument #1 ($array) must be an array or a sort flag that has not already been specified", null);
        }
        int size = arrays.get(0).size();
        for (PhpArray array : arrays) {
            if (array.size() != size) {
                throw new PhpError(PhpError.VALUE_ERROR, "Array sizes are inconsistent", null);
            }
        }
        @SuppressWarnings("unchecked")
        List<Object>[] columns = new List[arrays.size()];
        for (int c = 0; c < arrays.size(); c++) {
            List<Object> values = new ArrayList<>(size);
            for (Map.Entry<Object, Object> entry : arrays.get(c).entries()) {
                values.add(entry.getValue());
            }
            columns[c] = values;
        }
        Integer[] indices = new Integer[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }
        java.util.Arrays.sort(indices, (x, y) -> {
            for (int c = 0; c < columns.length; c++) {
                int cmp = compareColumn(columns[c].get(x), columns[c].get(y), types.get(c));
                if (cmp != 0) {
                    return orders.get(c) == 3 ? -cmp : cmp;
                }
            }
            return 0;
        });
        for (int c = 0; c < arrays.size(); c++) {
            List<Object> sorted = new ArrayList<>(size);
            for (Integer index : indices) {
                sorted.add(columns[c].get(index));
            }
            a[arrayPosition(a, c)] = PhpArray.ofList(sorted);
        }
        return true;
    }

    private static int arrayPosition(Object[] a, int column) {
        int seen = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] instanceof PhpArray) {
                if (seen == column) {
                    return i;
                }
                seen++;
            }
        }
        return a.length - 1;
    }

    private static int compareColumn(Object left, Object right, long type) {
        if (type == 1) {
            return Double.compare(PhpOps.toDouble(left), PhpOps.toDouble(right));
        }
        if (type == 2 || type == 5 || type == 6 || type == 10 || type == 14) {
            String l = PhpOps.toStr(left);
            String r = PhpOps.toStr(right);
            boolean foldCase = (type & 8) != 0;
            if ((type & ~8) == 6) {
                return StringFunctions.natCompare(l, r, foldCase);
            }
            if (foldCase) {
                l = dev.phpruntime.runtime.PhpStrings.lower(l);
                r = dev.phpruntime.runtime.PhpStrings.lower(r);
            }
            return l.compareTo(r);
        }
        return PhpOps.compare(left, right);
    }

    private static Object sort(Object[] a, Comparator<Map.Entry<Object, Object>> cmp, boolean keepKeys) {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(writable(a).entries());
        entries.sort(cmp); // stable, like PHP 8's sort
        a[0] = fromEntries(entries, keepKeys);
        return true;
    }

    private static Comparator<Map.Entry<Object, Object>> sortComparator(Object[] a, boolean keys) {
        long flags = a.length > 1 ? PhpOps.toLong(a[1]) : 0;
        return (x, y) -> {
            Object u = keys ? x.getKey() : x.getValue();
            Object v = keys ? y.getKey() : y.getValue();
            long mode = flags & ~8;
            if (mode == 1) {
                return Double.compare(PhpOps.toDouble(u), PhpOps.toDouble(v));
            }
            if (mode == 2 || mode == 5 || mode == 6) {
                String s = PhpOps.toStr(u);
                String t = PhpOps.toStr(v);
                if (mode == 6) {
                    return StringFunctions.natCompare(s, t, (flags & 8) != 0);
                }
                if ((flags & 8) != 0) {
                    s = dev.phpruntime.runtime.PhpStrings.lower(s);
                    t = dev.phpruntime.runtime.PhpStrings.lower(t);
                }
                return s.compareTo(t);
            }
            return PhpOps.compare(u, v);
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Scans in place and stops at the first match; copying the entries first made in_array O(n) allocation per call. */
    private static Object search(Object needle, PhpArray hay, boolean strict) {
        for (int pos = hay.firstPos(); pos >= 0; pos = hay.nextPos(pos)) {
            Object v = hay.rawValueAt(pos);
            if (v instanceof dev.phpruntime.runtime.PhpReference ref) {
                v = ref.getValue();
            }
            if (strict ? PhpOps.strictEquals(needle, v) : PhpOps.looseEquals(needle, v)) {
                return hay.keyAt(pos);
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
            putEntry(out, e.getKey(), e.getValue(), preserveKeys);
        }
        return out;
    }

    private static void putEntry(PhpArray out, Object key, Object value, boolean preserveKeys) {
        Object v = PhpOps.copyOnAssign(value);
        if (!preserveKeys && key instanceof Long) {
            out.append(v);
        } else {
            out.put(key, v);
        }
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
                arr(a, i, "array_replace").forEachEntry((key, value) -> out.put(key, PhpOps.copyOnAssign(value)));
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
            arr(a, 0, "array_change_key_case").forEachEntry((key, value) -> {
                Object k = key instanceof String s ? (upper ? dev.phpruntime.runtime.PhpStrings.upper(s)
                        : dev.phpruntime.runtime.PhpStrings.lower(s)) : key;
                out.put(k, PhpOps.copyOnAssign(value));
            });
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
        boolean direct = !first.hasObjectValue();
        for (PhpArray other : others) {
            if (other.hasObjectValue()) {
                direct = false;
                break;
            }
        }
        if (direct) {
            first.forEachEntry((key, value) -> {
                if (keepAgainst(key, value, others, valueSets, byKey, byValue, intersect)) {
                    out.put(key, PhpOps.copyOnAssign(value));
                }
            });
        } else {
            for (Map.Entry<Object, Object> e : first.entries()) {
                if (keepAgainst(e.getKey(), e.getValue(), others, valueSets, byKey, byValue, intersect)) {
                    out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
                }
            }
        }
        return out;
    }

    private static boolean keepAgainst(Object key, Object value, List<PhpArray> others,
                                       List<java.util.Set<String>> valueSets,
                                       boolean byKey, boolean byValue, boolean intersect) {
        for (int i = 0; i < others.size(); i++) {
            boolean found;
            if (byKey) {
                Object v = others.get(i).get(key);
                found = v != null && (!byValue || PhpOps.toStr(v).equals(PhpOps.toStr(value)));
            } else {
                found = valueSets.get(i).contains(PhpOps.toStr(value));
            }
            if (found != intersect) {
                return false;
            }
        }
        return true;
    }

    private static PhpArray replaceRecursive(PhpArray base, PhpArray with) {
        PhpArray out = base.share().forWrite();
        with.forEachEntry((key, value) -> {
            Object existing = out.get(key);
            if (existing instanceof PhpArray x && value instanceof PhpArray y) {
                out.put(key, replaceRecursive(x, y));
            } else {
                out.put(key, PhpOps.copyOnAssign(value));
            }
        });
        return out;
    }

    /** php_array_merge_recursive: string keys present in both are merged into a list, int keys append. */
    private static void mergeRecursive(PhpArray dest, PhpArray src) {
        src.forEachEntry((key, value) -> {
            if (key instanceof String && dest.containsKey(key)) {
                Object existing = dest.get(key);
                PhpArray merged;
                if (existing instanceof PhpArray x) {
                    merged = x.share().forWrite();
                } else {
                    merged = new PhpArray();
                    merged.append(existing);
                }
                if (value instanceof PhpArray y) {
                    mergeRecursive(merged, y);
                } else {
                    merged.append(PhpOps.copyOnAssign(value));
                }
                dest.put(key, merged);
            } else if (key instanceof String) {
                dest.put(key, PhpOps.copyOnAssign(value));
            } else {
                dest.append(PhpOps.copyOnAssign(value));
            }
        });
    }

    private static Object splice(Object[] a) {
        PhpArray x = writableInPlace(a);
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
        Object cbValue = a[0];
        PhpObject closureObj = cbValue instanceof PhpObject o && o.closure() != null ? o : null;
        PhpClosure closure = closureObj == null ? null : closureObj.closure();
        PhpCallable cb = cbValue == PhpNull.INSTANCE || closure != null ? null : PhpCallable.resolve(ctx, cbValue, null);
        if (a.length == 2) {
            PhpArray src = arr(a, 1, "array_map");
            PhpArray out = new PhpArray();
            if (cb == null && closure == null) {
                src.forEachEntry((key, value) -> out.put(key, PhpOps.copyOnAssign(value)));
            } else {
                PhpArray view = src.walkView();
                try {
                    for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                        out.put(view.keyAt(pos), callback1(ctx, closureObj, closure, cb, view.valueAt(pos)));
                    }
                } finally {
                    view.releaseShare();
                }
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
            if (cb == null && closure == null) {
                PhpArray row = new PhpArray();
                for (Object v : callArgs) {
                    row.append(PhpOps.copyOnAssign(v));
                }
                out.append(row);
            } else {
                out.append(closure != null
                        ? PhpCallable.invokeClosure(ctx, null, null, closureObj, closure, callArgs)
                        : cb.call(ctx, null, null, callArgs));
            }
        }
        return out;
    }

    private static PhpArray arrayFilter(PhpContext ctx, Object[] a) {
        PhpArray src = arr(a, 0, "array_filter");
        Object cbValue = a.length > 1 ? a[1] : PhpNull.INSTANCE;
        PhpObject closureObj = cbValue instanceof PhpObject o && o.closure() != null ? o : null;
        PhpClosure closure = closureObj == null ? null : closureObj.closure();
        PhpCallable cb = cbValue == PhpNull.INSTANCE || closure != null ? null : PhpCallable.resolve(ctx, cbValue, null);
        long mode = a.length > 2 ? PhpOps.toLong(a[2]) : 0L;
        PhpArray out = new PhpArray();
        if (cb == null && closure == null) {
            src.forEachEntry((key, value) -> {
                if (PhpOps.toBool(value)) {
                    out.put(key, PhpOps.copyOnAssign(value));
                }
            });
            return out;
        }
        PhpArray view = src.walkView();
        try {
            for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                Object key = view.keyAt(pos);
                Object raw = view.rawValueAt(pos);
                Object value = unwrap(raw);
                boolean keep;
                if (mode == 2L) { // ARRAY_FILTER_USE_KEY
                    keep = PhpOps.toBool(callback1(ctx, closureObj, closure, cb, key));
                } else if (mode == 1L) { // ARRAY_FILTER_USE_BOTH
                    keep = PhpOps.toBool(callback2(ctx, closureObj, closure, cb, value, key));
                } else {
                    keep = PhpOps.toBool(callback1(ctx, closureObj, closure, cb, value));
                }
                if (keep) {
                    // php copies the zval, so a reference cell stays a reference in the result.
                    out.put(key, PhpOps.copyOnAssign(raw));
                }
            }
        } finally {
            view.releaseShare();
        }
        return out;
    }

    private static Object arrayReduce(PhpContext ctx, Object[] a) {
        PhpArray src = arr(a, 0, "array_reduce");
        Object cbValue = a[1];
        PhpObject closureObj = cbValue instanceof PhpObject o && o.closure() != null ? o : null;
        PhpClosure closure = closureObj == null ? null : closureObj.closure();
        PhpCallable cb = closure == null ? PhpCallable.resolve(ctx, cbValue, null) : null;
        Object acc = a.length > 2 ? PhpOps.copyOnAssign(a[2]) : PhpNull.INSTANCE;
        PhpArray view = src.walkView();
        try {
            for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                acc = callback2(ctx, closureObj, closure, cb, acc, view.valueAt(pos));
            }
        } finally {
            view.releaseShare();
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
                && dev.phpruntime.nodes.PhpFunctionRootNode.of(cb.callTarget().getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode fn
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
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(writable(a).entries());
        entries.sort((e1, e2) -> {
            Object res = byKey
                    ? cb.call2(ctx, ctx.currentBuiltinCallSite(), e1.getKey(), e2.getKey())
                    : cb.call2(ctx, ctx.currentBuiltinCallSite(), e1.getValue(), e2.getValue());
            return PhpOps.compare(res, 0L);
        });
        if (keepKeys) {
            a[0] = fromEntries(entries, true);
        } else {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : entries) {
                out.append(PhpOps.copyOnAssign(e.getValue()));
            }
            a[0] = out;
        }
        return true;
    }

    private static Object findEntry(PhpContext ctx, Object[] a, String fn, boolean returnKey) {
        PhpArray src = arr(a, 0, fn);
        PhpObject closureObj = a[1] instanceof PhpObject o && o.closure() != null ? o : null;
        PhpClosure closure = closureObj == null ? null : closureObj.closure();
        PhpCallable cb = closure == null ? PhpCallable.resolve(ctx, a[1], null) : null;
        PhpArray view = src.walkView();
        try {
            for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                Object value = view.valueAt(pos);
                Object key = view.keyAt(pos);
                if (PhpOps.toBool(callback2(ctx, closureObj, closure, cb, value, key))) {
                    return returnKey ? key : PhpOps.copyOnAssign(value);
                }
            }
        } finally {
            view.releaseShare();
        }
        return PhpNull.INSTANCE;
    }

    private static boolean anyOrAll(PhpContext ctx, Object[] a, boolean isAny) {
        PhpArray src = arr(a, 0, isAny ? "array_any" : "array_all");
        PhpObject closureObj = a[1] instanceof PhpObject o && o.closure() != null ? o : null;
        PhpClosure closure = closureObj == null ? null : closureObj.closure();
        PhpCallable cb = closure == null ? PhpCallable.resolve(ctx, a[1], null) : null;
        PhpArray view = src.walkView();
        try {
            for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                Object value = view.valueAt(pos);
                Object key = view.keyAt(pos);
                boolean matched = PhpOps.toBool(callback2(ctx, closureObj, closure, cb, value, key));
                if (isAny && matched) {
                    return true;
                }
                if (!isAny && !matched) {
                    return false;
                }
            }
        } finally {
            view.releaseShare();
        }
        return !isAny;
    }

    /** A builtin callback resolved once: a closure object runs through its call target. */
    private static Object callback1(PhpContext ctx, PhpObject closureObj, PhpClosure closure, PhpCallable cb, Object arg) {
        return closure != null ? PhpCallable.invokeClosure1(ctx, null, closureObj, closure, arg) : cb.call1(ctx, null, arg);
    }

    private static Object callback2(PhpContext ctx, PhpObject closureObj, PhpClosure closure, PhpCallable cb,
                                    Object first, Object second) {
        return closure != null ? PhpCallable.invokeClosure2(ctx, null, closureObj, closure, first, second)
                : cb.call2(ctx, null, first, second);
    }

    /**
     * array_unique under SORT_STRING and SORT_LOCALE_STRING (the C locale compares bytes). The
     * string cast is the comparison key, so a hash set replaces php's O(n^2) scan of previous
     * values; php's own default does the same.
     */
    private static PhpArray uniqueByString(PhpArray src, boolean foldCase) {
        PhpArray out = new PhpArray();
        // toStr on an object runs __toString, which can write the array: the view keeps the walk
        // on php's by-value snapshot without an entries() list.
        PhpArray view = src.walkView();
        try {
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                Object raw = view.rawValueAt(pos);
                Object value = unwrap(raw);
                String key = PhpOps.toStr(value);
                if (foldCase) {
                    key = dev.phpruntime.runtime.PhpStrings.lower(key);
                }
                if (seen.add(key)) {
                    out.put(view.keyAt(pos), PhpOps.copyOnAssign(raw));
                }
            }
        } finally {
            view.releaseShare();
        }
        return out;
    }

    private static Object unwrap(Object value) {
        return value instanceof PhpReference ref ? ref.getValue() : value;
    }

    /** SORT_NUMERIC compares every value as a double, so integers beyond 2^53 are not exact. */
    private static boolean numericEquals(Object x, Object y) {
        return PhpOps.toDouble(x) == PhpOps.toDouble(y);
    }
}
