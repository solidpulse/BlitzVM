package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * preg_match, preg_match_all, preg_replace, preg_split, preg_quote, preg_grep.
 *
 * <p>Patterns go through {@link PcreTranslator} to java.util.regex. Offsets reported to
 * PHP (PREG_OFFSET_CAPTURE, preg_split's offsets) and the offset argument are always
 * byte offsets. Only {@code /u} decodes the pattern/subject; captured text is encoded
 * back to PHP bytes. Each Unicode call builds its byte-offset map in one linear pass.
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
        Builtins.def("preg_last_error", 0, 0, (ctx, a) -> (long) ContextState.of(ctx).pregLastError);
        Builtins.def("preg_last_error_msg", 0, 0, (ctx, a) -> switch (ContextState.of(ctx).pregLastError) {
            case 0 -> "No error";
            case 4 -> "Malformed UTF-8 characters, possibly incorrectly encoded";
            case 5 -> "The offset did not correspond to the beginning of a valid UTF-8 code point";
            case 6 -> "JIT stack limit exhausted";
            default -> "Internal error";
        });
    }

    // ------------------------------------------------------------------ compiling

    /** The compiled pattern, or null after emitting PHP's warning. */
    static PcreTranslator.Compiled compile(PhpContext ctx, String fn, String regex) {
        ContextState.of(ctx).pregLastError = 0;
        Object cached = CACHE.get(regex);
        if (cached == null) {
            try {
                cached = PcreTranslator.compile(regex);
            } catch (PcreTranslator.PatternError e) {
                cached = PhpStrings.fromJava(e.getMessage());
            }
            if (CACHE.size() >= CACHE_LIMIT) {
                CACHE.clear();
            }
            CACHE.put(regex, cached);
        }
        if (cached instanceof String error) {
            ContextState.of(ctx).pregLastError = 1;
            ctx.warning(fn + "(): " + error, 0);
            return null;
        }
        return (PcreTranslator.Compiled) cached;
    }

    // ------------------------------------------------------------------ byte offsets

    /** Converts between char indexes and UTF-8 byte offsets of one subject string. */
    static final class Offsets {
        final String text;
        private PhpStrings.Utf8Offsets offsets;
        private final int byteLength;
        final boolean utf;
        /**
         * Set when java.util.regex ran out of stack. It recurses once per iteration of a
         * repeated group where PCRE uses the heap; php fails such a match with
         * PREG_JIT_STACKLIMIT_ERROR once its JIT stack is full, so the caller does the same.
         */
        boolean limitHit;

        Offsets(String s, boolean utf, String text) {
            this.utf = utf;
            this.text = text;
            byteLength = s.length();
        }

        long toBytes(int charIndex) {
            if (!utf || charIndex == 0) {
                return charIndex;
            }
            return offsets().toBytes(charIndex);
        }

        int byteLength() {
            return byteLength;
        }

        int toChars(long byteOffset) {
            return !utf || byteOffset == 0 ? (int) byteOffset : offsets().toChars((int) byteOffset);
        }

        private PhpStrings.Utf8Offsets offsets() {
            if (offsets == null) {
                offsets = new PhpStrings.Utf8Offsets(text);
            }
            return offsets;
        }

        String php(String s) {
            return utf ? PhpStrings.fromJava(s) : s;
        }
    }

    private static Offsets subject(PhpContext ctx, PcreTranslator.Compiled re, String s) {
        String text = re.utf ? PhpStrings.toJavaUtf8OrNull(s) : s;
        if (text == null) {
            ContextState.of(ctx).pregLastError = 4;
            return null;
        }
        // toJavaUtf8OrNull returns the subject itself when it is ASCII, so an ASCII /u
        // subject needs no char-to-byte table and no encoding of matches back to PHP.
        return new Offsets(s, re.utf && text != s, text);
    }

    /** Java advances empty matches by UTF-16 units; PCRE /u advances by code points. */
    private static boolean find(Matcher matcher, Offsets off) {
        try {
            while (matcher.find()) {
                int start = matcher.start();
                if (!off.utf || start == off.text.length() || !Character.isLowSurrogate(off.text.charAt(start))) {
                    return true;
                }
            }
        } catch (StackOverflowError e) {
            off.limitHit = true;
        }
        return false;
    }

    private static boolean lookingAt(Matcher matcher, Offsets off) {
        try {
            return matcher.lookingAt();
        } catch (StackOverflowError e) {
            off.limitHit = true;
            return false;
        }
    }

    /** php's result for a match that ran out of stack: error 6, and the caller returns its failure value. */
    private static boolean limitHit(PhpContext ctx, Offsets off) {
        if (off.limitHit) {
            ContextState.of(ctx).pregLastError = 6;
        }
        return off.limitHit;
    }

    /**
     * Finds the next match at or after {@code from}. Patterns with a literal prefix jump
     * straight to its next occurrence and test the pattern there; java.util.regex would
     * try every index in the subject. A failed test resumes at the following occurrence.
     */
    private static boolean next(PcreTranslator.Compiled re, Matcher matcher, Offsets off, int from, int[] spans) {
        if (re.fast != null) {
            return re.fast.find(off.text, from, spans);
        }
        if (re.lead != null) {
            return nextByLead(re.lead, matcher, off, from);
        }
        if (re.prefix == null) {
            // No prefix: the matcher keeps its own position between find() calls.
            return find(matcher, off);
        }
        String text = off.text;
        int at = text.indexOf(re.prefix, from);
        if (at < 0) {
            return false;
        }
        for (;;) {
            matcher.region(at, text.length());
            if (lookingAt(matcher, off)) {
                return true;
            }
            if (off.limitHit) {
                return false;
            }
            at = text.indexOf(re.prefix, at + 1);
            if (at < 0) {
                return false;
            }
        }
    }

    /**
     * Finds the next match by its required literal: every match starts between
     * {@code maxLead} and {@code minLead} chars before an occurrence. Starts are tried in
     * increasing order and each at most once, so the first success is the leftmost match.
     */
    private static boolean nextByLead(PcreTranslator.Lead lead, Matcher matcher, Offsets off, int from) {
        String text = off.text;
        int n = text.length();
        int untried = from;
        int at = text.indexOf(lead.literal(), from + lead.minLead());
        while (at >= 0) {
            int last = at - lead.minLead();
            for (int start = Math.max(untried, at - lead.maxLead()); start <= last; start++) {
                if (off.utf && start < n && Character.isLowSurrogate(text.charAt(start))) {
                    continue;
                }
                matcher.region(start, n);
                if (lookingAt(matcher, off)) {
                    return true;
                }
                if (off.limitHit) {
                    return false;
                }
            }
            untried = Math.max(untried, last + 1);
            at = text.indexOf(lead.literal(), at + 1);
        }
        return false;
    }

    // ------------------------------------------------------------------ preg_match(_all)

    private static Object match(PhpContext ctx, Object[] a, boolean all) {
        String fn = all ? "preg_match_all" : "preg_match";
        String regex = PhpOps.toStr(a[0]);
        PcreTranslator.Compiled re = compile(ctx, fn, regex);
        if (re == null) {
            return false;
        }
        String subject = PhpOps.toStr(a[1]);
        long offset = a.length > 4 ? PhpOps.toLong(a[4]) : 0;
        if (offset < 0) {
            offset = -offset > subject.length() ? 0 : subject.length() + offset;
        }
        if (offset > subject.length()) {
            // php checks the offset before it looks at the subject's UTF-8, and rejects a
            // value past the end with PREG_INTERNAL_ERROR.
            ContextState.of(ctx).pregLastError = 1;
            if (a.length > 2) {
                a[2] = new PhpArray();
            }
            return false;
        }
        if (re.empty && !all && a.length <= 2 && offset == 0) {
            // The hot shape is preg_match('//u', $value): only the UTF-8 validity matters.
            if (re.utf && !PhpStrings.isUtf8(subject)) {
                ContextState.of(ctx).pregLastError = 4;
                return false;
            }
            return 1L;
        }
        long flags = a.length > 3 && a[3] != PhpNull.INSTANCE ? PhpOps.toLong(a[3]) : 0;
        Offsets off = subject(ctx, re, subject);
        if (off == null) {
            if (a.length > 2) {
                boolean setOrder = all && (flags & SET_ORDER) != 0;
                a[2] = all && !setOrder ? emptyColumns(re) : new PhpArray();
            }
            return false;
        }
        subject = off.text;
        int charOffset = off.toChars(offset);
        if (charOffset < 0) {
            ContextState.of(ctx).pregLastError = 5;
            if (a.length > 2) {
                a[2] = new PhpArray();
            }
            return false;
        }
        int[] spans = re.fast != null ? new int[2 * (re.fast.groups + 1)] : null;
        Matcher m = null;
        if (re.fast == null && (re.prefix == null || subject.indexOf(re.prefix) >= 0)) {
            m = openMatcher(ctx, re, subject, re.prefix == null ? charOffset : 0);
        }
        try {
            boolean offsetCapture = (flags & OFFSET_CAPTURE) != 0;
            boolean unmatchedNull = (flags & UNMATCHED_AS_NULL) != 0;
            if (!all) {
                boolean found = (m != null || re.fast != null) && next(re, m, off, charOffset, spans);
                if (a.length > 2) {
                    a[2] = found ? matchArray(re, m, subject, off, offsetCapture, unmatchedNull, spans) : new PhpArray();
                }
                if (limitHit(ctx, off)) {
                    return false;
                }
                return found ? 1L : 0L;
            }
            boolean setOrder = (flags & SET_ORDER) != 0;
            if (setOrder && (flags & PATTERN_ORDER) != 0) {
                ctx.warning("preg_match_all(): Invalid flags specified", 0);
                return false;
            }
            boolean wantMatches = a.length > 2;
            List<PhpArray> sets = null;
            PhpArray[] columns = null;
            long count = 0;
            int pos = charOffset;
            while ((m != null || re.fast != null) && next(re, m, off, pos, spans)) {
                count++;
                pos = end(re, m, 0, spans);
                if (!wantMatches) {
                    continue;
                }
                if (setOrder) {
                    if (sets == null) {
                        sets = new ArrayList<>();
                    }
                    sets.add(matchArray(re, m, subject, off, offsetCapture, unmatchedNull, spans));
                } else {
                    if (columns == null) {
                        columns = new PhpArray[re.groupCount + 1];
                        for (int g = 0; g <= re.groupCount; g++) {
                            columns[g] = new PhpArray();
                        }
                    }
                    for (int g = 0; g <= re.groupCount; g++) {
                        columns[g].append(groupValue(re, m, g, subject, off, offsetCapture, unmatchedNull, spans));
                    }
                }
            }
            if (a.length > 2) {
                PhpArray result = new PhpArray();
                if (setOrder) {
                    if (sets != null) {
                        sets.forEach(result::append);
                    }
                } else {
                    if (columns == null) {
                        columns = new PhpArray[re.groupCount + 1];
                        for (int g = 0; g <= re.groupCount; g++) {
                            columns[g] = new PhpArray();
                        }
                    }
                    for (int g = 0; g <= re.groupCount; g++) {
                        if (re.names[g] != null) {
                            result.put(re.names[g], columns[g].share());
                        }
                        result.put((long) g, columns[g]);
                    }
                }
                a[2] = result;
            }
            if (limitHit(ctx, off)) {
                // php keeps the matches found before the failure.
                return false;
            }
            return count;
        } finally {
            if (m != null) {
                ctx.releaseMatcher(m);
            }
        }
    }

    /** preg_match_all's pattern-order shape when the subject could not be decoded. */
    private static PhpArray emptyColumns(PcreTranslator.Compiled re) {
        PhpArray result = new PhpArray();
        for (int g = 0; g <= re.groupCount; g++) {
            PhpArray column = new PhpArray();
            if (re.names[g] != null) {
                result.put(re.names[g], column.share());
            }
            result.put((long) g, column);
        }
        return result;
    }

    /** A matcher for one preg call, reusing this context's matcher for the same pattern. */
    private static Matcher openMatcher(PhpContext ctx, PcreTranslator.Compiled re, String subject, int from) {
        Matcher m = ctx.acquireMatcher(re.pattern, subject);
        m.useTransparentBounds(true).useAnchoringBounds(false);
        if (re.prefix == null) {
            m.region(from, subject.length());
        }
        return m;
    }

    /** One match as preg_match returns it: trailing groups that did not take part are dropped. */
    private static PhpArray matchArray(PcreTranslator.Compiled re, Matcher m, String subject, Offsets off,
                                       boolean offsetCapture, boolean unmatchedNull, int[] spans) {
        int count = unmatchedNull ? re.groupCount + 1
                : spans != null ? matchedCount(spans, re.groupCount) : re.matchedCount(m);
        boolean named = spans != null && names(re, count);
        PhpArray arr = spans != null ? PhpArray.withCapacity(count + (named ? count : 0), named) : new PhpArray();
        for (int g = 0; g < count; g++) {
            Object v = groupValue(re, m, g, subject, off, offsetCapture, unmatchedNull, spans);
            if (re.names[g] != null) {
                arr.put(re.names[g], v instanceof PhpArray pa ? pa.share() : v);
            }
            arr.put((long) g, v);
        }
        if (spans == null) {
            String mark = re.mark(m);
            if (mark != null) {
                arr.put("MARK", mark);
            }
        }
        return arr;
    }

    private static boolean names(PcreTranslator.Compiled re, int count) {
        for (int g = 0; g < count; g++) {
            if (re.names[g] != null) {
                return true;
            }
        }
        return false;
    }

    /** PCRE's return code from fast spans: one more than the highest group that matched. */
    private static int matchedCount(int[] spans, int groupCount) {
        for (int g = groupCount; g >= 1; g--) {
            if (spans[2 * g] >= 0) {
                return g + 1;
            }
        }
        return 1;
    }

    private static int start(PcreTranslator.Compiled re, Matcher m, int g, int[] spans) {
        return spans != null ? spans[2 * g] : re.start(m, g);
    }

    private static int end(PcreTranslator.Compiled re, Matcher m, int g, int[] spans) {
        return spans != null ? spans[2 * g + 1] : re.end(m, g);
    }

    private static Object groupValue(PcreTranslator.Compiled re, Matcher m, int g, String subject, Offsets off,
                                     boolean offsetCapture, boolean unmatchedNull, int[] spans) {
        int start = start(re, m, g, spans);
        Object text = start < 0 ? (unmatchedNull ? PhpNull.INSTANCE : "")
                : off.php(subject.substring(start, end(re, m, g, spans)));
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
        if (limit == 0) {
            return subject; // compile still runs, but a limit of zero never looks at it
        }
        Offsets off = subject(ctx, re, subject);
        if (off == null) {
            return null;
        }
        String original = subject;
        subject = off.text;
        if (re.fast == null && re.prefix != null && subject.indexOf(re.prefix) < 0) {
            return original;
        }
        int[] spans = re.fast != null ? new int[2 * (re.fast.groups + 1)] : null;
        Matcher m = re.fast == null ? openMatcher(ctx, re, subject, 0) : null;
        try {
            StringBuilder sb = null;
            int last = 0;
            long done = 0;
            int pos = 0;
            while ((limit < 0 || done < limit) && next(re, m, off, pos, spans)) {
                if (sb == null) {
                    sb = new StringBuilder(subject.length() + 16);
                }
                sb.append(off.php(subject.substring(last, start(re, m, 0, spans))));
                expand(sb, replacement, re, m, subject, off, spans);
                last = end(re, m, 0, spans);
                pos = last;
                done++;
            }
            count[0] += done;
            if (limitHit(ctx, off)) {
                return null;
            }
            if (sb == null) {
                return original;
            }
            sb.append(off.php(subject.substring(last)));
            return sb.toString();
        } finally {
            if (m != null) {
                ctx.releaseMatcher(m);
            }
        }
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
        if (limit == 0) {
            return subject;
        }
        Offsets off = subject(ctx, re, subject);
        if (off == null) {
            return null;
        }
        String original = subject;
        subject = off.text;
        if (re.fast == null && re.prefix != null && subject.indexOf(re.prefix) < 0) {
            return original;
        }
        int[] spans = re.fast != null ? new int[2 * (re.fast.groups + 1)] : null;
        Matcher m = re.fast == null ? openMatcher(ctx, re, subject, 0) : null;
        try {
            StringBuilder sb = null;
            int last = 0;
            long done = 0;
            int pos = 0;
            while ((limit < 0 || done < limit) && next(re, m, off, pos, spans)) {
                if (sb == null) {
                    sb = new StringBuilder(subject.length() + 16);
                }
                sb.append(off.php(subject.substring(last, start(re, m, 0, spans))));
                PhpArray matches = matchArray(re, m, subject, off, offsetCapture, unmatchedNull, spans);
                sb.append(PhpOps.toStr(cb.call(ctx, null, null, matches)));
                last = end(re, m, 0, spans);
                pos = last;
                done++;
            }
            count[0] += done;
            if (limitHit(ctx, off)) {
                return null;
            }
            if (sb == null) {
                return original;
            }
            sb.append(off.php(subject.substring(last)));
            return sb.toString();
        } finally {
            if (m != null) {
                ctx.releaseMatcher(m);
            }
        }
    }

    /** PHP's replacement syntax: \n, $n and ${n} with one or two digits; \\ and \$ escape. */
    private static void expand(StringBuilder sb, String rep, PcreTranslator.Compiled re, Matcher m, String subject, Offsets off, int[] spans) {
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
                        int s = start(re, m, g, spans);
                        if (s >= 0) {
                            sb.append(off.php(subject.substring(s, end(re, m, g, spans))));
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
        String regex = PhpOps.toStr(a[0]);
        PcreTranslator.Compiled re = compile(ctx, "preg_split", regex);
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
        if (limit == 1) {
            // php returns the whole subject without splitting, and therefore without
            // validating it under /u.
            PhpArray out = new PhpArray();
            if (!noEmpty || !subject.isEmpty()) {
                if (offsets) {
                    PhpArray pair = new PhpArray();
                    pair.append(subject);
                    pair.append(0L);
                    out.append(pair);
                } else {
                    out.append(subject);
                }
            }
            return out;
        }
        Offsets off = subject(ctx, re, subject);
        if (off == null) {
            return false;
        }
        subject = off.text;
        PhpArray out = new PhpArray();
        if (re.fast == null && re.prefix != null && subject.indexOf(re.prefix) < 0) {
            if (!noEmpty || subject.length() > 0) {
                out.append(piece(subject, 0, subject.length(), off, offsets));
            }
            return out;
        }
        int last = 0;
        int splits = 0;
        if (limit != 1) {
            int[] spans = re.fast != null ? new int[2 * (re.fast.groups + 1)] : null;
            Matcher m = re.fast == null ? openMatcher(ctx, re, subject, 0) : null;
            try {
                int pos = 0;
                while ((limit == -1 || limit > 1) && next(re, m, off, pos, spans)) {
                    int matchStart = start(re, m, 0, spans);
                    int matchEnd = end(re, m, 0, spans);
                    splits++;
                    if (!noEmpty || matchStart != last) {
                        out.append(piece(subject, last, matchStart, off, offsets));
                        if (limit != -1) {
                            limit--;
                        }
                    }
                    if (delim) {
                        int count = spans != null ? matchedCount(spans, re.groupCount) : re.matchedCount(m);
                        for (int g = 1; g < count; g++) {
                            int s = start(re, m, g, spans);
                            int e = end(re, m, g, spans);
                            if (!noEmpty || (s >= 0 && e > s)) {
                                out.append(s < 0 ? piece(subject, 0, 0, off, offsets) : piece(subject, s, e, off, offsets));
                            }
                        }
                    }
                    last = matchEnd;
                    pos = last;
                }
                if (limitHit(ctx, off)) {
                    return false;
                }
            } finally {
                if (m != null) {
                    ctx.releaseMatcher(m);
                }
            }
        }
        if (!noEmpty || last < subject.length()) {
            out.append(piece(subject, last, subject.length(), off, offsets));
        }
        return out;
    }

    private static Object piece(String s, int start, int end, Offsets off, boolean withOffset) {
        String text = off.php(s.substring(start, end));
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
        String regex = PhpOps.toStr(a[0]);
        PcreTranslator.Compiled re = compile(ctx, "preg_grep", regex);
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
            Offsets off = subject(ctx, re, PhpOps.toStr(e.getValue()));
            if (off == null) {
                return false;
            }
            boolean found;
            int[] spans = re.fast != null ? new int[2 * (re.fast.groups + 1)] : null;
            Matcher m = re.fast == null ? openMatcher(ctx, re, off.text, 0) : null;
            try {
                if (re.fast != null) {
                    found = next(re, null, off, 0, spans);
                } else if (re.prefix == null) {
                    found = find(m, off);
                } else {
                    found = next(re, m, off, 0, null);
                }
                if (limitHit(ctx, off)) {
                    return false;
                }
            } finally {
                if (m != null) {
                    ctx.releaseMatcher(m);
                }
            }
            if (found != invert) {
                out.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
            }
        }
        return out;
    }
}
