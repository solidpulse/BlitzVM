package dev.phpruntime.builtins;

/**
 * Regex-free matchers for the simple pattern shapes that dominate hot PHP code.
 *
 * <p>{@link PcreTranslator} builds one of these once per compiled pattern and
 * {@link PregFunctions} asks it for match spans instead of running
 * {@link java.util.regex.Matcher}. Every shape here is a conservative parse of the
 * translated pattern: when the body is not exactly understood, {@link #analyze} returns
 * null and the pattern takes the ordinary regex path. Spans are char indexes into the
 * Java text; the caller maps them to PHP byte offsets and encodes captures back.
 *
 * <p>Instances are immutable and shared between requests. A {@code find} call fills a
 * caller-owned {@code int[]} with {@code start,end} pairs for group 0 and each capture
 * group; {@code -1} marks a group that did not participate.
 */
abstract class PcreFast {

    /** Number of capture groups, not counting group 0. */
    final int groups;

    PcreFast(int groups) {
        this.groups = groups;
    }

    /** Fills {@code out[0..2*(groups+1))}; returns false when nothing matches. */
    abstract boolean find(String text, int from, int[] out);

    /**
     * Chooses a matcher for the translated body, or null. The body is Java regex text after
     * {@link PcreTranslator} rewrote PCRE constructs, so it contains no delimiters.
     */
    static PcreFast analyze(String body, boolean caseless, boolean multiline, boolean dotall,
                            boolean utf, boolean anchored, boolean dollarEndOnly) {
        if (body.isEmpty() || dollarEndOnly) {
            return null;
        }
        if (multiline && !caseless && !utf && !anchored
                && body.equals("^[\\s\\r\\n]?---[\\s\\r\\n]?$")) {
            return new FrontMatter();
        }
        if (anchored && !multiline && !dotall && !caseless && !utf
                && body.equals("\\s*#.*$")) {
            return new WsHash();
        }
        if (utf && !caseless && !multiline && !dotall && !anchored
                && body.equals("^" + YAML_KEY)) {
            return new YamlKey();
        }
        // A leading ^ anchors a shape at the start of the subject; with /m it can also
        // match after a newline and the scanners below do not model that.
        boolean atStart = false;
        int i = 0;
        if (body.charAt(0) == '^') {
            if (multiline) {
                return null;
            }
            atStart = true;
            i = 1;
            if (i == body.length()) {
                return null;
            }
        }
        boolean atFrom = anchored && !atStart;
        String rest = body.substring(i);
        PcreFast fast = literal(rest, 0, caseless, utf, atStart, atFrom);
        if (fast != null) {
            return fast;
        }
        fast = classShape(rest, caseless, utf, atStart, atFrom);
        if (fast != null) {
            return fast;
        }
        fast = anchoredNumber(rest, caseless, utf, multiline, dotall);
        if (fast != null) {
            return fast;
        }
        return null;
    }

    // ------------------------------------------------------------------ literal

    /** A pure literal string, or null when the body has any other construct. */
    private static PcreFast literal(String body, int start, boolean caseless, boolean utf,
                                    boolean atStart, boolean atFrom) {
        LiteralText parsed = literalPrefix(body, start, utf);
        if (parsed == null || parsed.text.isEmpty() || parsed.end != body.length()) {
            return null;
        }
        if (caseless && utf) {
            return null; // Java folds Unicode with the UNICODE_CASE flag; declining is safer
        }
        return new Literal(parsed.text, caseless, atStart, atFrom);
    }

    private record LiteralText(String text, int end) {
    }

    /**
     * The longest literal at {@code start}: stops at the first metacharacter. Returns null
     * when the escape there is a class, an assertion or a backreference.
     */
    private static LiteralText literalPrefix(String s, int start, boolean utf) {
        StringBuilder sb = new StringBuilder(s.length() - start);
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 < s.length() && s.charAt(i + 1) == 'Q') {
                    int close = s.indexOf("\\E", i + 2);
                    sb.append(close < 0 ? s.substring(i + 2) : s.substring(i + 2, close));
                    i = close < 0 ? s.length() : close + 2;
                    continue;
                }
                int[] e = literalEscape(s, i, utf);
                if (e == null) {
                    return null;
                }
                if (e[0] >= 0) {
                    sb.append((char) e[0]);
                } else {
                    sb.appendCodePoint(-e[0] - 1);
                }
                i = e[1];
                continue;
            }
            if (metachar(c)) {
                break;
            }
            sb.append(c);
            i++;
        }
        return new LiteralText(sb.toString(), i);
    }

    /** The whole body as a literal, or null. */
    private static String literalText(String body, int start, boolean utf) {
        LiteralText parsed = literalPrefix(body, start, utf);
        return parsed == null || parsed.end < body.length() ? null : parsed.text();
    }

    private static boolean metachar(char c) {
        return switch (c) {
            case '\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}' -> true;
            default -> false;
        };
    }

    /**
     * Decodes the escape at {@code i} as a single literal. Returns {codePoint, end}, with
     * -codePoint-1 for values above the BMP, or null for classes/assertions/backrefs.
     */
    private static int[] literalEscape(String s, int i, boolean utf) {
        if (i + 1 >= s.length()) {
            return null;
        }
        char e = s.charAt(i + 1);
        switch (e) {
            case 't': return new int[]{'\t', i + 2};
            case 'n': return new int[]{'\n', i + 2};
            case 'r': return new int[]{'\r', i + 2};
            case 'f': return new int[]{'\f', i + 2};
            case 'a': return new int[]{7, i + 2};
            case 'e': return new int[]{27, i + 2};
            case 'x': return hexEscape(s, i + 1, utf);
            default: {
                if (Character.isLetterOrDigit(e)) {
                    return null; // \d, \w, \s, \p, \N, \R, backrefs
                }
                if (!utf && e > 255) {
                    return null;
                }
                return new int[]{e, i + 2};
            }
        }
    }

    /** \x{...}, \xHH or \xH at {@code i} (just after the x). */
    private static int[] hexEscape(String s, int i, boolean utf) {
        if (i < s.length() && s.charAt(i) == '{') {
            int close = s.indexOf('}', i + 1);
            if (close < 0) {
                return null;
            }
            int value = 0;
            for (int k = i + 1; k < close; k++) {
                int d = Character.digit(s.charAt(k), 16);
                if (d < 0) {
                    return null;
                }
                value = value * 16 + d;
                if (value > 0x10FFFF) {
                    return null;
                }
            }
            if (!utf && value > 255) {
                return null;
            }
            return new int[]{value < 0x10000 ? value : -value - 1, close + 1};
        }
        int end = i;
        int value = 0;
        while (end < s.length() && end - i < 2 && Character.digit(s.charAt(end), 16) >= 0) {
            value = value * 16 + Character.digit(s.charAt(end), 16);
            end++;
        }
        if (end == i || (!utf && value > 255)) {
            return null;
        }
        return new int[]{value, end};
    }

    private static final class Literal extends PcreFast {
        private final String text;
        private final boolean caseless;
        private final boolean atStart;
        private final boolean atFrom;

        Literal(String text, boolean caseless, boolean atStart, boolean atFrom) {
            super(0);
            this.text = text;
            this.caseless = caseless;
            this.atStart = atStart;
            this.atFrom = atFrom;
        }

        @Override
        boolean find(String subject, int from, int[] out) {
            int start;
            if (atStart) {
                if (from > 0) {
                    return false;
                }
                start = 0;
            } else if (atFrom) {
                start = from;
            } else {
                start = -1;
            }
            if (start >= 0) {
                if (!regionMatches(subject, start, text, caseless)) {
                    return false;
                }
                out[0] = start;
                out[1] = start + text.length();
                return true;
            }
            int i = caseless ? indexOfCaseless(subject, from, text) : subject.indexOf(text, from);
            if (i < 0) {
                return false;
            }
            out[0] = i;
            out[1] = i + text.length();
            return true;
        }
    }

    private static boolean regionMatches(String text, int at, String lit, boolean caseless) {
        int n = text.length();
        if (at < 0 || at + lit.length() > n) {
            return false;
        }
        for (int i = 0; i < lit.length(); i++) {
            char a = text.charAt(at + i);
            char b = lit.charAt(i);
            if (a != b && (!caseless || asciiLower(a) != asciiLower(b))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfCaseless(String text, int from, String lit) {
        int last = text.length() - lit.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (regionMatches(text, i, lit, true)) {
                return i;
            }
        }
        return -1;
    }

    private static char asciiLower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    // ------------------------------------------------------------------ character classes

    private static PcreFast classShape(String body, boolean caseless, boolean utf,
                                       boolean atStart, boolean atFrom) {
        if (caseless && utf) {
            return null; // PCRE folds Unicode case variants (Kelvin sign for [k], long s for [s])
        }
        Atom atom = atom(body, 0, caseless, utf);
        if (atom == null) {
            return null;
        }
        int i = atom.end;
        boolean plus = false;
        if (i < body.length() && body.charAt(i) == '+') {
            plus = true;
            i++;
            if (i < body.length() && body.charAt(i) == '+') {
                i++; // possessive: the same whole-run match for one greedy atom
            } else if (i < body.length() && body.charAt(i) == '?') {
                return null; // lazy: a shorter run is a different match
            }
        }
        if (!plus) {
            if (i != body.length()) {
                return null;
            }
            return new SingleClass(atom.set, atStart, atFrom);
        }
        if (i == body.length()) {
            return new ClassRun(atom.set, atStart, atFrom);
        }
        String lit = literalText(body, i, utf);
        if (lit == null || lit.isEmpty() || atom.set.matches(lit.charAt(0))) {
            return null;
        }
        return new ClassRunLiteral(atom.set, lit, atStart, atFrom);
    }

    /** A class or a single literal character (optionally wrapped in a non-capturing group). */
    private record Atom(ClassSet set, int end) {
    }

    private static Atom atom(String body, int start, boolean caseless, boolean utf) {
        if (start >= body.length()) {
            return null;
        }
        char c = body.charAt(start);
        if (c == '[') {
            ClassSet set = parseClass(body, start, caseless, utf);
            return set == null ? null : new Atom(set, set.end);
        }
        if (body.startsWith("(?:", start)) {
            int end = body.indexOf(')', start + 3);
            if (end < 0) {
                return null;
            }
            Atom inner = atom(body, start + 3, caseless, utf);
            if (inner == null || inner.end != end) {
                return null;
            }
            return new Atom(inner.set, end + 1);
        }
        int[] e;
        if (c == '\\') {
            e = literalEscape(body, start, utf);
            if (e == null) {
                return null;
            }
        } else {
            if (metachar(c)) {
                return null;
            }
            e = new int[]{c, start + 1};
        }
        long[] bits = new long[4];
        if (!addChar(bits, e[0], utf)) {
            return null;
        }
        return new Atom(new ClassSet(bits, false, e[1]), e[1]);
    }

    /** A class with no repetition: exactly one character. */
    private static final class SingleClass extends PcreFast {
        private final ClassSet set;
        private final boolean atStart;
        private final boolean atFrom;

        SingleClass(ClassSet set, boolean atStart, boolean atFrom) {
            super(0);
            this.set = set;
            this.atStart = atStart;
            this.atFrom = atFrom;
        }

        @Override
        boolean find(String text, int from, int[] out) {
            int i = from;
            if (atStart) {
                if (from > 0) {
                    return false;
                }
                i = 0;
            }
            int n = text.length();
            for (; i < n; i++) {
                if (set.matches(text.charAt(i))) {
                    out[0] = i;
                    out[1] = i + 1;
                    return true;
                }
                if (atStart || atFrom) {
                    return false;
                }
            }
            return false;
        }
    }

    /** A character class followed by +: the whole run, at least one character. */
    private static final class ClassRun extends PcreFast {
        private final ClassSet set;
        private final boolean atStart;
        private final boolean atFrom;

        ClassRun(ClassSet set, boolean atStart, boolean atFrom) {
            super(0);
            this.set = set;
            this.atStart = atStart;
            this.atFrom = atFrom;
        }

        @Override
        boolean find(String text, int from, int[] out) {
            int i = from;
            if (atStart) {
                if (from > 0) {
                    return false;
                }
                i = 0;
            }
            int n = text.length();
            while (i < n && !set.matches(text.charAt(i))) {
                if (atStart || atFrom) {
                    return false;
                }
                i++;
            }
            if (i >= n) {
                return false;
            }
            int start = i;
            do {
                i++;
            } while (i < n && set.matches(text.charAt(i)));
            out[0] = start;
            out[1] = i;
            return true;
        }
    }

    /** A greedy class run followed by a literal, e.g. {@code [ \t]+#}. */
    private static final class ClassRunLiteral extends PcreFast {
        private final ClassSet set;
        private final String literal;
        private final boolean atStart;
        private final boolean atFrom;

        ClassRunLiteral(ClassSet set, String literal, boolean atStart, boolean atFrom) {
            super(0);
            this.set = set;
            this.literal = literal;
            this.atStart = atStart;
            this.atFrom = atFrom;
        }

        @Override
        boolean find(String text, int from, int[] out) {
            int i = from;
            if (atStart) {
                if (from > 0) {
                    return false;
                }
                i = 0;
            }
            int n = text.length();
            while (i < n) {
                if (!set.matches(text.charAt(i))) {
                    if (atStart || atFrom) {
                        return false;
                    }
                    i++;
                    continue;
                }
                int start = i;
                do {
                    i++;
                } while (i < n && set.matches(text.charAt(i)));
                if (regionMatches(text, i, literal, false)) {
                    out[0] = start;
                    out[1] = i + literal.length();
                    return true;
                }
                if (atStart || atFrom) {
                    return false;
                }
                // Every start inside this run ends at the same i, and the literal does not
                // start with a class character, so no other start can succeed here.
            }
            return false;
        }
    }

    /** A bitmap class over PHP's byte values (or ASCII when /u). */
    private static final class ClassSet {
        final long[] bits;
        final boolean negated;
        final int end;

        ClassSet(long[] bits, boolean negated, int end) {
            this.bits = bits;
            this.negated = negated;
            this.end = end;
        }

        boolean matches(char c) {
            boolean in = c < 256 && (bits[c >>> 6] & (1L << (c & 63))) != 0;
            return negated != in;
        }
    }

    /**
     * Parses {@code [..]} at {@code start}. Returns null when the class has a construct this
     * scanner does not model (Unicode members under /u, property classes, intersections, ...).
     * {@code caseless} is folded into the bitmap for ASCII members only.
     */
    private static ClassSet parseClass(String s, int start, boolean caseless, boolean utf) {
        int n = s.length();
        int i = start + 1;
        boolean negated = false;
        if (i < n && s.charAt(i) == '^') {
            negated = true;
            i++;
        }
        if (negated && utf) {
            return null; // a code point beyond ASCII is a single match; the scanner sees chars
        }
        long[] bits = new long[4];
        boolean any = false;
        boolean first = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ']' && !first) {
                if (!any) {
                    return null;
                }
                if (caseless) {
                    foldAscii(bits);
                }
                return new ClassSet(bits, negated, i + 1);
            }
            first = false;
            if (c == '\\') {
                int end = classEscape(s, i, utf, bits);
                if (end < 0) {
                    return null;
                }
                any = true;
                i = end;
                continue;
            }
            int lo = c;
            i++;
            if (i < n && s.charAt(i) == '-' && i + 1 < n && s.charAt(i + 1) != ']') {
                i++;
                int hi;
                if (s.charAt(i) == '\\') {
                    int[] e = singleEscape(s, i, utf);
                    if (e == null) {
                        return null;
                    }
                    hi = e[0];
                    i = e[1];
                } else {
                    hi = s.charAt(i);
                    i++;
                }
                if (!addRange(bits, lo, hi, utf)) {
                    return null;
                }
            } else if (!addChar(bits, lo, utf)) {
                return null;
            }
            any = true;
        }
        return null;
    }

    /** A class escape (which may add many chars) at {@code i}; returns the new index or -1. */
    private static int classEscape(String s, int i, boolean utf, long[] bits) {
        char e = i + 1 < s.length() ? s.charAt(i + 1) : 0;
        switch (e) {
            case 'd' -> {
                if (utf) {
                    return -1; // Java's \d is Unicode with the UNICODE_CHARACTER_CLASS flag
                }
                addRangeUnchecked(bits, '0', '9');
                return i + 2;
            }
            case 'w' -> {
                if (utf) {
                    return -1;
                }
                addRangeUnchecked(bits, '0', '9');
                addRangeUnchecked(bits, 'A', 'Z');
                addRangeUnchecked(bits, '_', '_');
                addRangeUnchecked(bits, 'a', 'z');
                return i + 2;
            }
            case 's' -> {
                if (utf) {
                    return -1;
                }
                for (char c : new char[]{' ', '\t', '\n', 0x0B, '\f', '\r'}) {
                    addRangeUnchecked(bits, c, c);
                }
                return i + 2;
            }
            case 'D', 'W', 'S', 'p', 'P', 'h', 'H', 'v', 'V', 'N', 'R', 'X', 'Q' -> {
                return -1;
            }
            default -> {
                int[] one = singleEscape(s, i, utf);
                if (one == null || !addChar(bits, one[0], utf)) {
                    return -1;
                }
                return one[1];
            }
        }
    }

    /** A single-character escape at {@code i}; returns {codePoint, newIndex}. */
    private static int[] singleEscape(String s, int i, boolean utf) {
        if (i + 1 >= s.length() || s.charAt(i) != '\\') {
            return null;
        }
        char e = s.charAt(i + 1);
        switch (e) {
            case 't': return new int[]{'\t', i + 2};
            case 'n': return new int[]{'\n', i + 2};
            case 'r': return new int[]{'\r', i + 2};
            case 'f': return new int[]{'\f', i + 2};
            case 'a': return new int[]{7, i + 2};
            case 'e': return new int[]{27, i + 2};
            case 'x': return hexEscape(s, i + 1, utf);
            default: {
                if (Character.isLetterOrDigit(e)) {
                    return null;
                }
                if (!utf && e > 255) {
                    return null;
                }
                return new int[]{e, i + 2};
            }
        }
    }

    private static boolean addChar(long[] bits, int c, boolean utf) {
        if (c > 255 || (utf && c > 127)) {
            return false;
        }
        bits[c >>> 6] |= 1L << (c & 63);
        return true;
    }

    private static boolean addRange(long[] bits, int lo, int hi, boolean utf) {
        if (lo > hi || hi > 255 || (utf && hi > 127)) {
            return false;
        }
        addRangeUnchecked(bits, lo, hi);
        return true;
    }

    private static void addRangeUnchecked(long[] bits, int lo, int hi) {
        for (int c = lo; c <= hi; c++) {
            bits[c >>> 6] |= 1L << (c & 63);
        }
    }

    private static void foldAscii(long[] bits) {
        for (int c = 'A'; c <= 'Z'; c++) {
            if ((bits[c >>> 6] & (1L << (c & 63))) != 0) {
                int lower = c + 32;
                bits[lower >>> 6] |= 1L << (lower & 63);
            }
        }
        for (int c = 'a'; c <= 'z'; c++) {
            if ((bits[c >>> 6] & (1L << (c & 63))) != 0) {
                int upper = c - 32;
                bits[upper >>> 6] |= 1L << (upper & 63);
            }
        }
    }

    // ------------------------------------------------------------------ anchored number

    /**
     * {@code ^} optional sign, a literal, a class run and {@code $}: the octal and decimal
     * checks symfony/yaml runs on every scalar. The optional sign and the class run may or
     * may not be captured; only that capture group is reported.
     */
    private static PcreFast anchoredNumber(String rest, boolean caseless, boolean utf,
                                           boolean multiline, boolean dotall) {
        if (caseless || multiline || dotall) {
            return null;
        }
        int i = 0;
        boolean sign = false;
        if (rest.startsWith("(?:\\+|-)?", i)) {
            sign = true;
            i += 9;
        } else if (rest.startsWith("[+-]?", i)) {
            sign = true;
            i += 5;
        }
        LiteralText lit = literalPrefix(rest, i, utf);
        if (lit == null || lit.text.isEmpty()) {
            return null;
        }
        i = lit.end;
        boolean captured = false;
        Atom atom;
        if (i < rest.length() && rest.charAt(i) == '(') {
            captured = true;
            i++;
            atom = atom(rest, i, false, utf);
            if (atom == null) {
                return null;
            }
            i = atom.end;
        } else {
            atom = atom(rest, i, false, utf);
            if (atom == null) {
                return null;
            }
            i = atom.end;
        }
        boolean plus = false;
        if (i < rest.length() && rest.charAt(i) == '+') {
            plus = true;
            i++;
            if (i < rest.length() && rest.charAt(i) == '+') {
                i++;
            }
        }
        if (!plus) {
            return null;
        }
        if (captured) {
            if (i >= rest.length() || rest.charAt(i) != ')') {
                return null;
            }
            i++;
        }
        if (i >= rest.length() || rest.charAt(i) != '$') {
            return null;
        }
        i++;
        if (i != rest.length()) {
            return null;
        }
        return new AnchoredNumber(sign, lit.text, atom.set, captured);
    }

    private static final class AnchoredNumber extends PcreFast {
        private final boolean sign;
        private final String literal;
        private final ClassSet set;
        private final boolean captured;

        AnchoredNumber(boolean sign, String literal, ClassSet set, boolean captured) {
            super(captured ? 1 : 0);
            this.sign = sign;
            this.literal = literal;
            this.set = set;
            this.captured = captured;
        }

        @Override
        boolean find(String text, int from, int[] out) {
            if (from > 0) {
                return false;
            }
            int n = text.length();
            int i = 0;
            if (sign && i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                i++;
            }
            if (!text.startsWith(literal, i)) {
                return false;
            }
            i += literal.length();
            int start = i;
            while (i < n && set.matches(text.charAt(i))) {
                i++;
            }
            if (i == start) {
                return false;
            }
            int end = i;
            if (end != n && !(end == n - 1 && text.charAt(end) == '\n')) {
                return false;
            }
            out[0] = 0;
            out[1] = end;
            if (captured) {
                out[2] = start;
                out[3] = end;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ YAML key line

    /**
     * symfony/yaml's key pattern, translated. It parses every line of front matter, so a
     * scanner is much cheaper than the backtracking regex engine.
     */
    private static final String YAML_KEY =
            "((?:![^\\s]++\\s++)?(?:(?:\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"|'([^']*+(?:''[^']*+)*+)')"
                    + "|[^ '\"\\[\\{!].*?)) *\\:(( |\\t)++(.+))?$";

    private static final class YamlKey extends PcreFast {
        YamlKey() {
            super(6);
        }

        @Override
        boolean find(String text, int from, int[] out) {
            if (from > 0) {
                return false;
            }
            int n = text.length();
            int tagEnd = -1;
            if (n > 0 && text.charAt(0) == '!') {
                int j = 1;
                while (j < n && !isSpace(text.charAt(j))) {
                    j++;
                }
                if (j > 1) {
                    int k = j;
                    while (k < n && isSpace(text.charAt(k))) {
                        k++;
                    }
                    if (k > j) {
                        tagEnd = k;
                    }
                }
            }
            if (tagEnd >= 0) {
                int end = keyLine(text, tagEnd, out);
                if (end >= 0) {
                    out[0] = 0;
                    out[1] = end;
                    out[2] = 0; // group 1 runs from the tag through the key
                    return true;
                }
            }
            int end = keyLine(text, 0, out);
            if (end >= 0) {
                out[0] = 0;
                out[1] = end;
                out[2] = 0;
                return true;
            }
            return false;
        }

        /**
         * Parses the key and the value that follows it at {@code keyStart}; returns the end
         * of the whole match or -1.
         */
        private static int keyLine(String text, int keyStart, int[] out) {
            int n = text.length();
            for (int g = 2; g < out.length; g++) {
                out[g] = -1;
            }
            if (keyStart >= n) {
                return -1;
            }
            char c0 = text.charAt(keyStart);
            if (c0 == '"') {
                int p = keyStart + 1;
                while (p < n) {
                    char c = text.charAt(p);
                    if (c == '"') {
                        break;
                    }
                    if (c == '\\') {
                        if (p + 1 >= n || text.charAt(p + 1) == '\n') {
                            return -1;
                        }
                        p += 2;
                    } else {
                        p++;
                    }
                }
                if (p >= n) {
                    return -1;
                }
                out[4] = keyStart + 1;
                out[5] = p;
                out[3] = p + 1; // group 1 includes the closing quote
                return afterKey(text, p + 1, out);
            }
            if (c0 == '\'') {
                int p = keyStart + 1;
                int content = p;
                while (true) {
                    while (p < n && text.charAt(p) != '\'') {
                        p++;
                    }
                    if (p >= n) {
                        return -1;
                    }
                    if (p + 1 < n && text.charAt(p + 1) == '\'') {
                        p += 2;
                        continue;
                    }
                    break;
                }
                out[6] = content;
                out[7] = p;
                out[3] = p + 1; // group 1 includes the closing quote
                return afterKey(text, p + 1, out);
            }
            if (c0 == ' ' || c0 == '[' || c0 == '{' || c0 == '!') {
                return -1;
            }
            // [^ '"\[\{!].*? followed by " *:"; try colons left to right.
            for (int colon = keyStart + 1; colon < n; colon++) {
                if (text.charAt(colon) != ':') {
                    continue;
                }
                int keyEnd = colon;
                while (keyEnd > keyStart && text.charAt(keyEnd - 1) == ' ') {
                    keyEnd--;
                }
                boolean newline = false;
                for (int k = keyStart + 1; k < keyEnd; k++) {
                    if (text.charAt(k) == '\n') {
                        newline = true; // . does not match \n
                        break;
                    }
                }
                if (newline) {
                    return -1;
                }
                out[3] = keyEnd;
                int end = afterKey(text, keyEnd, out);
                if (end >= 0) {
                    return end;
                }
            }
            return -1;
        }

        /** Consumes the spaces before the colon and the optional value after it. */
        private static int afterKey(String text, int from, int[] out) {
            int n = text.length();
            int p = from;
            while (p < n && text.charAt(p) == ' ') {
                p++;
            }
            if (p >= n || text.charAt(p) != ':') {
                return -1;
            }
            int valueStart = p + 1;
            int i = valueStart;
            if (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                int wsStart = i;
                while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                    i++;
                }
                if (i >= n) {
                    return -1; // ( |\t)++ consumed everything, .+ had nothing left
                }
                int valStart = i;
                int j = i;
                while (j < n && text.charAt(j) != '\n') {
                    j++;
                }
                if (j != n && j != n - 1) {
                    return -1;
                }
                if (j == valStart) {
                    return -1;
                }
                out[8] = valueStart;
                out[9] = j;
                out[10] = i - 1;
                out[11] = i;
                out[12] = valStart;
                out[13] = j;
                return j;
            }
            if (i == n || (i == n - 1 && text.charAt(i) == '\n')) {
                return i;
            }
            return -1;
        }
    }

    // ------------------------------------------------------------------ /\s*#.*$/A

    /** The comment stripper symfony/yaml applies to a scalar: whitespace, '#', rest of line. */
    private static final class WsHash extends PcreFast {
        WsHash() {
            super(0);
        }

        @Override
        boolean find(String text, int from, int[] out) {
            int n = text.length();
            int i = from;
            while (i < n && isSpace(text.charAt(i))) {
                i++;
            }
            if (i >= n || text.charAt(i) != '#') {
                return false;
            }
            int j = i + 1;
            while (j < n && text.charAt(j) != '\n') {
                j++;
            }
            if (j != n && j != n - 1) {
                return false; // $ without /m cannot match before an internal newline
            }
            out[0] = from;
            out[1] = j;
            return true;
        }
    }

    // ------------------------------------------------------------------ front matter split

    /**
     * {@code /^[\s\r\n]?---[\s\r\n]?$/sm}: a line that is exactly three dashes, allowing
     * one whitespace character on either side (which is how the delimiter absorbs the
     * newline next to it).
     */
    private static final class FrontMatter extends PcreFast {
        FrontMatter() {
            super(0);
        }

        @Override
        boolean find(String text, int from, int[] out) {
            int n = text.length();
            // The old regex path found candidates with indexOf("---") and this keeps that
            // vectorised scan; walking 5 KB article bodies one char at a time was slower
            // than the regex it replaced.
            for (int p = text.indexOf("---", from); p >= 0; p = text.indexOf("---", p + 1)) {
                for (int lead = 1; lead >= 0; lead--) {
                    int s = p - lead;
                    if (s < from || (s > 0 && text.charAt(s - 1) != '\n')) {
                        continue; // ^ only matches at a line start
                    }
                    if (lead == 1 && !isSpace(text.charAt(s))) {
                        continue;
                    }
                    int q = p + 3;
                    for (int trail = 1; trail >= 0; trail--) {
                        int e = q + trail;
                        if (e > n || (trail == 1 && (q >= n || !isSpace(text.charAt(q))))) {
                            continue;
                        }
                        if (e == n || text.charAt(e) == '\n') {
                            out[0] = s;
                            out[1] = e;
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /** PCRE's \s without UCP: space, \t, \n, \r, \f and \v. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }
}
