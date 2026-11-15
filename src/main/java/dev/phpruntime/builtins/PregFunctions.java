package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * preg_match, preg_match_all, preg_replace, preg_split, preg_quote, preg_grep.
 *
 * <p>Patterns go through {@link PcreTranslator} to java.util.regex. Offsets reported to
 * PHP (PREG_OFFSET_CAPTURE, preg_split's offsets) and the offset argument are UTF-8 byte
 * offsets, as in PHP; internally strings are Java strings. Without the {@code u}
 * modifier PHP matches bytes while this matches characters, so {@code .} consumes a whole
 * multi-byte character here.
 *
 * <p>TODO(callables): preg_replace_callback and preg_replace_callback_array need closures.
 */
final class PregFunctions {

    static final long PATTERN_ORDER = 1;
    static final long SET_ORDER = 2;
    static final long OFFSET_CAPTURE = 256;
    static final long UNMATCHED_AS_NULL = 512;
    static final long SPLIT_NO_EMPTY = 1;
    static final long SPLIT_DELIM_CAPTURE = 2;
    static final long SPLIT_OFFSET_CAPTURE = 4;
    static final long GREP_INVERT = 1;

    private static final int CACHE_LIMIT = 4096;
    /** Compiled patterns (or the error text) by PHP pattern string, shared by all requests. */
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    private PregFunctions() {
    }

    static void register() {
        Builtins.defRef("preg_match", 2, 5, 1 << 2, (ctx, a) -> match(ctx, a, false));
        Builtins.defRef("preg_match_all", 2, 5, 1 << 2, (ctx, a) -> match(ctx, a, true));
        Builtins.defRef("preg_replace", 3, 5, 1 << 4, PregFunctions::replace);
        Builtins.defRef("preg_replace_callback", 3, 6, 1 << 4, PregFunctions::replaceCallback);
        Builtins.defRef("preg_replace_callback_array", 2, 5, 1 << 3, PregFunctions::replaceCallbackArray);
        Builtins.def("preg_split", 2, 4, PregFunctions::split);
        Builtins.def("preg_quote", 1, 2, (ctx, a) -> quote(PhpOps.toStr(a[0]),
                a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toStr(a[1]) : ""));
        Builtins.def("preg_grep", 2, 3, PregFunctions::grep);
        Builtins.def("preg_last_error", 0, 0, (ctx, a) -> 0L);
        Builtins.def("preg_last_error_msg", 0, 0, (ctx, a) -> "No error");
    }

    // ------------------------------------------------------------------ compiling

    /** The compiled pattern, or null after emitting PHP's warning. */
    static PcreTranslator.Compiled compile(PhpContext ctx, String fn, String regex) {
        Object cached = CACHE.get(regex);
        if (cached == null) {
            try {
                cached = PcreTranslator.compile(regex);
            } catch (PcreTranslator.PatternError e) {
                cached = e.getMessage();
            }
            if (CACHE.size() >= CACHE_LIMIT) {
                CACHE.clear();
            }
            CACHE.put(regex, cached);
        }
        if (cached instanceof String error) {
            ctx.warning(fn + "(): " + error, 0);
            return null;
        }
        return (PcreTranslator.Compiled) cached;
    }

    // ------------------------------------------------------------------ byte offsets

    /** Converts between char indexes and UTF-8 byte offsets of one subject string. */
    static final class Offsets {
        private final String s;
        private int[] bytes;

        Offsets(String s) {
            this.s = s;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) >= 0x80) {
                    bytes = new int[s.length() + 1];
                    for (int k = 0; k < s.length(); k++) {
                        char c = s.charAt(k);
                        int w = c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isHighSurrogate(c) ? 4 : Character.isLowSurrogate(c) ? 0 : 3;
                        bytes[k + 1] = bytes[k] + w;
                    }
                    return;
                }
            }
        }

        long toBytes(int charIndex) {
            return bytes == null ? charIndex : bytes[charIndex];
        }

        int byteLength() {
            return bytes == null ? s.length() : bytes[s.length()];
        }

        /** The char index at or after a byte offset. */
        int toChars(long byteOffset) {
            if (bytes == null) {
                return (int) byteOffset;
            }
            int i = 0;
            while (i < s.length() && bytes[i] < byteOffset) {
                i++;
            }
            return i;
        }
    }

    // ------------------------------------------------------------------ preg_match(_all)

    private static Object match(PhpContext ctx, Object[] a, boolean all) {
        String fn = all ? "preg_match_all" : "preg_match";
        PcreTranslator.Compiled re = compile(ctx, fn, PhpOps.toStr(a[0]));
        if (re == null) {
            return false;
        }
        String subject = PhpOps.toStr(a[1]);
        long flags = a.length > 3 && a[3] != PhpNull.INSTANCE ? PhpOps.toLong(a[3]) : 0;
        Offsets off = new Offsets(subject);
        long offset = a.length > 4 ? PhpOps.toLong(a[4]) : 0;
        if (offset < 0) {
            offset = -offset > off.byteLength() ? 0 : off.byteLength() + offset;
        }
        if (offset > off.byteLength()) {
            if (a.length > 2) {
                a[2] = new PhpArray();
            }
            return false;
        }
        Matcher m = re.pattern.matcher(subject);
        m.useTransparentBounds(true).useAnchoringBounds(false).region(off.toChars(offset), subject.length());
        boolean offsetCapture = (flags & OFFSET_CAPTURE) != 0;
        boolean unmatchedNull = (flags & UNMATCHED_AS_NULL) != 0;
        if (!all) {
            boolean found = m.find();
            if (a.length > 2) {
                a[2] = found ? matchArray(re, m, subject, off, offsetCapture, unmatchedNull) : new PhpArray();
            }
            return found ? 1L : 0L;
        }
        boolean setOrder = (flags & SET_ORDER) != 0;
        if (setOrder && (flags & PATTERN_ORDER) != 0) {
            ctx.warning("preg_match_all(): Invalid flags specified", 0);
            return false;
        }
        List<PhpArray> sets = new ArrayList<>();
        PhpArray[] columns = new PhpArray[re.groupCount + 1];
        for (int g = 0; g <= re.groupCount; g++) {
            columns[g] = new PhpArray();
        }
        long count = 0;
        while (m.find()) {
            count++;
            if (setOrder) {
                sets.add(matchArray(re, m, subject, off, offsetCapture, unmatchedNull));
            } else {
                for (int g = 0; g <= re.groupCount; g++) {
                    columns[g].append(groupValue(re, m, g, subject, off, offsetCapture, unmatchedNull));
                }
            }
        }
        if (a.length > 2) {
            PhpArray result = new PhpArray();
            if (setOrder) {
                sets.forEach(result::append);
            } else {
                for (int g = 0; g <= re.groupCount; g++) {
                    if (re.names[g] != null) {
                        result.put(re.names[g], columns[g].share());
                    }
                    result.put((long) g, columns[g]);
                }
            }
            a[2] = result;
        }
        return count;
    }

    /** One match as preg_match returns it: trailing groups that did not take part are dropped. */
    private static PhpArray matchArray(PcreTranslator.Compiled re, Matcher m, String subject, Offsets off,
                                       boolean offsetCapture, boolean unmatchedNull) {
        int count = unmatchedNull ? re.groupCount + 1 : re.matchedCount(m);
        PhpArray arr = new PhpArray();
        for (int g = 0; g < count; g++) {
            Object v = groupValue(re, m, g, subject, off, offsetCapture, unmatchedNull);
            if (re.names[g] != null) {
                arr.put(re.names[g], v instanceof PhpArray pa ? pa.share() : v);
            }
            arr.put((long) g, v);
        }
        return arr;
    }

    private static Object groupValue(PcreTranslator.Compiled re, Matcher m, int g, String subject, Offsets off,
                                     boolean offsetCapture, boolean unmatchedNull) {
        int start = re.start(m, g);
        Object text = start < 0 ? (unmatchedNull ? PhpNull.INSTANCE : "") : subject.substring(start, re.end(m, g));
        if (!offsetCapture) {
            return text;
        }
        PhpArray pair = new PhpArray();
        pair.append(text);
        pair.append(start < 0 ? -1L : off.toBytes(start));
        return pair;
    }

    // ------------------------------------------------------------------ preg_replace

    private static Object replace(PhpContext ctx, Object[] a) {
        Object pattern = a[0];
        Object replacement = a[1];
        Object subject = a[2];
        long limit = a.length > 3 && a[3] != PhpNull.INSTANCE ? PhpOps.toLong(a[3]) : -1;
        if (!(pattern instanceof PhpArray) && replacement instanceof PhpArray) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "preg_replace(): Argument #1 ($pattern) must be of type array when argument #2 ($replacement) is an array, string given", null);
        }
        long[] count = {0};
        Object result;
        if (subject instanceof PhpArray subjects) {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : subjects.entries()) {
                String r = replaceAll(ctx, pattern, replacement, PhpOps.toStr(e.getValue()), limit, count);
                if (r != null) {
                    out.put(e.getKey(), r);
                }
            }
            result = out;
        } else {
            String r = replaceAll(ctx, pattern, replacement, PhpOps.toStr(subject), limit, count);
            result = r == null ? PhpNull.INSTANCE : r;
        }
        if (a.length > 4) {
            a[4] = count[0];
        }
        return result;
    }

    private static String replaceAll(PhpContext ctx, Object pattern, Object replacement, String subject, long limit, long[] count) {
        if (!(pattern instanceof PhpArray patterns)) {
            return replaceOne(ctx, PhpOps.toStr(pattern), PhpOps.toStr(replacement), subject, limit, count);
        }
        List<Object> reps = replacement instanceof PhpArray ra ? ra.values() : null;
        int i = 0;
        for (Object p : patterns.values()) {
            String rep = reps == null ? PhpOps.toStr(replacement) : i < reps.size() ? PhpOps.toStr(reps.get(i)) : "";
            i++;
            subject = replaceOne(ctx, PhpOps.toStr(p), rep, subject, limit, count);
            if (subject == null) {
                return null;
            }
        }
        return subject;
    }

    private static String replaceOne(PhpContext ctx, String regex, String replacement, String subject, long limit, long[] count) {
        PcreTranslator.Compiled re = compile(ctx, "preg_replace", regex);
        if (re == null) {
            return null;
        }
        Matcher m = re.pattern.matcher(subject);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        long done = 0;
        while ((limit < 0 || done < limit) && m.find()) {
            sb.append(subject, last, m.start());
            expand(sb, replacement, re, m, subject);
            last = m.end();
            done++;
        }
        sb.append(subject, last, subject.length());
        count[0] += done;
        return sb.toString();
    }

    private static Object replaceCallback(PhpContext ctx, Object[] a) {
        Object pattern = a[0];
        PhpCallable cb = PhpCallable.resolve(ctx, a[1], null);
        Object subject = a[2];
        long limit = a.length > 3 && a[3] != PhpNull.INSTANCE ? PhpOps.toLong(a[3]) : -1;
        long flags = a.length > 5 ? PhpOps.toLong(a[5]) : 0;
        long[] count = {0};
        Object result;
        if (subject instanceof PhpArray subjects) {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : subjects.entries()) {
                String r = replaceCallbackAll(ctx, pattern, cb, PhpOps.toStr(e.getValue()), limit, flags, count);
                if (r != null) {
                    out.put(e.getKey(), r);
                }
            }
            result = out;
        } else {
            String r = replaceCallbackAll(ctx, pattern, cb, PhpOps.toStr(subject), limit, flags, count);
            result = r == null ? PhpNull.INSTANCE : r;
        }
        if (a.length > 4) {
            a[4] = count[0];
        }
        return result;
    }

    private static String replaceCallbackAll(PhpContext ctx, Object pattern, PhpCallable cb, String subject,
                                               long limit, long flags, long[] count) {
        if (!(pattern instanceof PhpArray patterns)) {
            return replaceCallbackOne(ctx, "preg_replace_callback", PhpOps.toStr(pattern), cb, subject, limit, flags, count);
        }
        for (Object p : patterns.values()) {
            subject = replaceCallbackOne(ctx, "preg_replace_callback", PhpOps.toStr(p), cb, subject, limit, flags, count);
            if (subject == null) {
                return null;
            }
        }
        return subject;
    }

    private static Object replaceCallbackArray(PhpContext ctx, Object[] a) {
        if (!(a[0] instanceof PhpArray patternMap)) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "preg_replace_callback_array(): Argument #1 ($pattern) must be of type array, "
                            + PhpOps.typeName(a[0]) + " given", null);
        }
        List<Map.Entry<String, PhpCallable>> pairs = new ArrayList<>(patternMap.size());
        for (Map.Entry<Object, Object> e : patternMap.entries()) {
            pairs.add(Map.entry(PhpOps.toStr(e.getKey()), PhpCallable.resolve(ctx, e.getValue(), null)));
        }
        Object subject = a[1];
        long limit = a.length > 2 && a[2] != PhpNull.INSTANCE ? PhpOps.toLong(a[2]) : -1;
        long flags = a.length > 4 ? PhpOps.toLong(a[4]) : 0;
        long[] count = {0};
        Object result;
        if (subject instanceof PhpArray subjects) {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : subjects.entries()) {
                String s = PhpOps.toStr(e.getValue());
                for (Map.Entry<String, PhpCallable> p : pairs) {
                    s = replaceCallbackOne(ctx, "preg_replace_callback_array", p.getKey(), p.getValue(), s, limit, flags, count);
                    if (s == null) {
                        break;
                    }
                }
                if (s != null) {
                    out.put(e.getKey(), s);
                }
            }
            result = out;
        } else {
            String s = PhpOps.toStr(subject);
            for (Map.Entry<String, PhpCallable> p : pairs) {
                s = replaceCallbackOne(ctx, "preg_replace_callback_array", p.getKey(), p.getValue(), s, limit, flags, count);
                if (s == null) {
                    break;
                }
            }
            result = s == null ? PhpNull.INSTANCE : s;
        }
        if (a.length > 3) {
            a[3] = count[0];
        }
        return result;
    }

    private static String replaceCallbackOne(PhpContext ctx, String fnName, String regex, PhpCallable cb,
                                               String subject, long limit, long flags, long[] count) {
        PcreTranslator.Compiled re = compile(ctx, fnName, regex);
        if (re == null) {
            return null;
        }
        boolean offsetCapture = (flags & OFFSET_CAPTURE) != 0;
        boolean unmatchedNull = (flags & UNMATCHED_AS_NULL) != 0;
        Offsets off = offsetCapture ? new Offsets(subject) : null;
        Matcher m = re.pattern.matcher(subject);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        long done = 0;
        while ((limit < 0 || done < limit) && m.find()) {
            sb.append(subject, last, m.start());
            PhpArray matches = matchArray(re, m, subject, off, offsetCapture, unmatchedNull);
            sb.append(PhpOps.toStr(cb.call(ctx, null, null, matches)));
            last = m.end();
            done++;
        }
        sb.append(subject, last, subject.length());
        count[0] += done;
        return sb.toString();
    }

    /** PHP's replacement syntax: \n, $n and ${n} with one or two digits; \\ and \$ escape. */
    private static void expand(StringBuilder sb, String rep, PcreTranslator.Compiled re, Matcher m, String subject) {
        int n = rep.length();
        char last = 0;
        for (int i = 0; i < n; ) {
            char c = rep.charAt(i);
            if (c == '\\' || c == '$') {
                if (last == '\\') {
                    sb.setCharAt(sb.length() - 1, c);
                    i++;
                    last = 0;
                    continue;
                }
                int j = i + 1;
                boolean braced = c == '$' && j < n && rep.charAt(j) == '{';
                if (braced) {
                    j++;
                }
                int digitsStart = j;
                while (j < n && j - digitsStart < 2 && Character.isDigit(rep.charAt(j))) {
                    j++;
                }
                boolean ok = j > digitsStart && (!braced || (j < n && rep.charAt(j) == '}'));
                if (ok) {
                    int g = Integer.parseInt(rep.substring(digitsStart, j));
                    if (braced) {
                        j++;
                    }
                    if (g <= re.groupCount) {
                        int s = re.start(m, g);
                        if (s >= 0) {
                            sb.append(subject, s, re.end(m, g));
                        }
                    }
                    i = j;
                    last = 0;
                    continue;
                }
            }
            sb.append(c);
            last = c;
            i++;
        }
    }

    // ------------------------------------------------------------------ preg_split

    private static Object split(PhpContext ctx, Object[] a) {
        PcreTranslator.Compiled re = compile(ctx, "preg_split", PhpOps.toStr(a[0]));
        if (re == null) {
            return false;
        }
        String subject = PhpOps.toStr(a[1]);
        long limit = a.length > 2 && a[2] != PhpNull.INSTANCE ? PhpOps.toLong(a[2]) : -1;
        if (limit == 0) {
            limit = -1;
        }
        long flags = a.length > 3 ? PhpOps.toLong(a[3]) : 0;
        boolean noEmpty = (flags & SPLIT_NO_EMPTY) != 0;
        boolean delim = (flags & SPLIT_DELIM_CAPTURE) != 0;
        boolean offsets = (flags & SPLIT_OFFSET_CAPTURE) != 0;
        Offsets off = new Offsets(subject);
        PhpArray out = new PhpArray();
        int last = 0;
        if (limit != 1) {
            Matcher m = re.pattern.matcher(subject);
            while ((limit == -1 || limit > 1) && m.find()) {
                if (!noEmpty || m.start() != last) {
                    out.append(piece(subject, last, m.start(), off, offsets));
                    if (limit != -1) {
                        limit--;
                    }
                }
                if (delim) {
                    int count = re.matchedCount(m);
                    for (int g = 1; g < count; g++) {
                        int s = re.start(m, g);
                        int e = re.end(m, g);
                        if (!noEmpty || (s >= 0 && e > s)) {
                            out.append(s < 0 ? piece(subject, 0, 0, off, offsets) : piece(subject, s, e, off, offsets));
                        }
                    }
                }
                last = m.end();
            }
        }
        if (!noEmpty || last < subject.length()) {
            out.append(piece(subject, last, subject.length(), off, offsets));
        }
        return out;
    }

    private static Object piece(String s, int start, int end, Offsets off, boolean withOffset) {
        String text = s.substring(start, end);
        if (!withOffset) {
            return text;
        }
        PhpArray pair = new PhpArray();
        pair.append(text);
        pair.append(off.toBytes(start));
        return pair;
    }

    // ------------------------------------------------------------------ preg_quote, preg_grep

    static String quote(String s, String delimiter) {
        char d = delimiter.isEmpty() ? 0 : delimiter.charAt(0);
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '.', '\\', '+', '*', '?', '[', '^', ']', '$', '(', ')', '{', '}', '=', '!', '<', '>', '|', ':', '-', '#' ->
                        sb.append('\\').append(c);
                case 0 -> sb.append("\\000");
                default -> {
                    if (c == d) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static Object grep(PhpContext ctx, Object[] a) {
        PcreTranslator.Compiled re = compile(ctx, "preg_grep", PhpOps.toStr(a[0]));
        if (re == null) {
            return false;
        }
        if (!(a[1] instanceof PhpArray input)) {
            throw new PhpError(PhpError.TYPE_ERROR, "preg_grep(): Argument #2 ($array) must be of type array, "
                    + PhpOps.typeName(a[1]) + " given", null);
        }
        boolean invert = a.length > 2 && (PhpOps.toLong(a[2]) & GREP_INVERT) != 0;
        PhpArray out = new PhpArray();
        for (Map.Entry<Object, Object> e : input.entries()) {
            boolean found = re.pattern.matcher(PhpOps.toStr(e.getValue())).find();
            if (found != invert) {
                out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
            }
        }
        return out;
    }
}
