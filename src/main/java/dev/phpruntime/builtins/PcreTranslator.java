package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpStrings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns a PHP (PCRE2) pattern such as {@code /(?P<name>\w+)/iu} into a
 * {@link java.util.regex.Pattern}.
 *
 * <p>Java's syntax is close to PCRE's, so most of the pattern is copied through. The
 * differences handled here: delimiters and modifiers, named groups (Java names allow
 * no underscores, so groups are plain and the names are kept on the side), {@code x}
 * mode (Java's COMMENTS flag also ignores whitespace inside classes, PCRE does not),
 * the {@code U} modifier, {@code D}, {@code A}, POSIX classes, literal braces, {@code &&}
 * and {@code [} inside classes, {@code \g}, {@code \k}, {@code (?P=name)}, {@code \N},
 * script properties, conditionals and branch-reset groups.
 *
 * <p>Conditionals and branch resets have no Java equivalent and are rewritten.
 * {@code (?(?=X)yes|no)} becomes {@code (?:(?=X)yes|(?!X)no)}. For {@code (?(1)yes|no)}
 * an empty marker group is added at the end of group 1; Java fails a backreference to a
 * group that did not participate, so {@code (?=\m)} tests whether group 1 is set. Because
 * of these extra groups, a PHP group number can map to several Java groups; the result
 * is the first one that participated.
 *
 * <p>Not supported, reported as errors: recursion and subroutine calls, backtracking
 * verbs other than (*FAIL), {@code \K}, and {@code (?(R)...)}/{@code (?(DEFINE)...)}.
 */
final class PcreTranslator {

    /** A compiled pattern plus the mapping from PHP group numbers to Java groups. */
    static final class Compiled {
        final Pattern pattern;
        /** Number of PHP capture groups, not counting group 0. */
        final int groupCount;
        /** For PHP group g (1-based), the Java groups that may hold it, first match wins. */
        final int[][] javaGroups;
        /** For PHP group g, its name or null. */
        final String[] names;
        final boolean utf;
        final String decodedPattern;

        Compiled(Pattern pattern, int groupCount, int[][] javaGroups, String[] names, boolean utf, String decodedPattern) {
            this.pattern = pattern;
            this.groupCount = groupCount;
            this.javaGroups = javaGroups;
            this.names = names;
            this.utf = utf;
            this.decodedPattern = decodedPattern;
        }

        int start(Matcher m, int g) {
            if (g == 0) {
                return m.start();
            }
            for (int j : javaGroups[g]) {
                int s = m.start(j);
                if (s >= 0) {
                    return s;
                }
            }
            return -1;
        }

        int end(Matcher m, int g) {
            if (g == 0) {
                return m.end();
            }
            for (int j : javaGroups[g]) {
                if (m.start(j) >= 0) {
                    return m.end(j);
                }
            }
            return -1;
        }

        /** PCRE's return code: one more than the highest group that participated. */
        int matchedCount(Matcher m) {
            for (int g = groupCount; g >= 1; g--) {
                if (start(m, g) >= 0) {
                    return g + 1;
                }
            }
            return 1;
        }
    }

    /** A pattern PHP would reject; the message is PHP's warning text after "preg_xxx(): ". */
    static final class PatternError extends Exception {
        PatternError(String message) {
            super(message, null, false, false);
        }
    }

    /** A construct PCRE accepts but this translation cannot express. */
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\d+(,\\d*)?}");
    private static final Pattern COND_REF = Pattern.compile("\\(\\?\\((\\d+|<[^>]+>|'[^']+'|[A-Za-z_]\\w*)\\)");
    private static final Set<String> GENERAL_CATEGORIES = Set.of(
            "C", "Cc", "Cf", "Cn", "Co", "Cs", "L", "Ll", "Lm", "Lo", "Lt", "Lu", "M", "Mc", "Me", "Mn",
            "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Pe", "Pf", "Pi", "Po", "Ps", "S", "Sc", "Sk", "Sm", "So",
            "Z", "Zl", "Zp", "Zs");

    // ------------------------------------------------------------------ entry point

    static Compiled compile(String regex) throws PatternError {
        int p = 0;
        int n = regex.length();
        while (p < n && isSpace(regex.charAt(p))) {
            p++;
        }
        if (p == n) {
            throw new PatternError("Empty regular expression");
        }
        char start = regex.charAt(p);
        if (Character.isLetterOrDigit(start) || start == '\\' || start == 0) {
            throw new PatternError("Delimiter must not be alphanumeric, backslash, or NUL byte");
        }
        p++;
        char end = switch (start) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            default -> start;
        };
        int bodyStart = p;
        if (start == end) {
            while (p < n) {
                char c = regex.charAt(p);
                if (c == '\\' && p + 1 < n) {
                    p++;
                } else if (c == end) {
                    break;
                }
                p++;
            }
            if (p >= n) {
                throw new PatternError("No ending delimiter '" + end + "' found");
            }
        } else {
            int depth = 1;
            while (p < n) {
                char c = regex.charAt(p);
                if (c == '\\' && p + 1 < n) {
                    p++;
                } else if (c == end && --depth <= 0) {
                    break;
                } else if (c == start) {
                    depth++;
                }
                p++;
            }
            if (p >= n) {
                throw new PatternError("No ending matching delimiter '" + end + "' found");
            }
        }
        String body = regex.substring(bodyStart, p);
        PcreTranslator t = new PcreTranslator(body);
        for (int i = p + 1; i < n; i++) {
            char c = regex.charAt(i);
            switch (c) {
                case 'i' -> t.caseless = true;
                case 'm' -> t.multiline = true;
                case 's' -> t.dotall = true;
                case 'x' -> t.extended = true;
                case 'u' -> t.utf = true;
                case 'U' -> t.ungreedy = true;
                case 'D' -> t.dollarEndOnly = true;
                case 'A' -> t.anchored = true;
                case 'n' -> t.noAutoCapture = true;
                case 'S', 'X', 'J', ' ', '\n', '\r' -> {
                }
                case 0 -> throw new PatternError("NUL is not a valid modifier");
                default -> throw new PatternError("Unknown modifier '" + c + "'");
            }
        }
        return t.translate();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == '\f';
    }

    // ------------------------------------------------------------------ translator state

    private String src;
    private int pos;
    private final StringBuilder out = new StringBuilder();

    private boolean caseless;
    private boolean multiline;
    private boolean dotall;
    private boolean extended;
    private boolean utf;
    private boolean ungreedy;
    private boolean dollarEndOnly;
    private boolean anchored;
    private boolean noAutoCapture;

    private int javaGroups;
    private int userGroups;
    private final List<List<Integer>> userToJava = new ArrayList<>();
    private final Map<Integer, String> userNames = new HashMap<>();
    private final Map<String, Integer> nameToUser = new HashMap<>();
    /** PHP groups referenced by a (?(n)...) condition: they get an empty marker group. */
    private final Set<Integer> markedNumbers = new HashSet<>();
    private final Set<String> markedNames = new HashSet<>();
    private final Map<Integer, List<Integer>> markers = new HashMap<>();
    /** Backreferences resolved after the whole pattern is read (they may point forward). */
    private final List<Object> backrefs = new ArrayList<>();

    private PcreTranslator(String src) {
        this.src = src;
        userToJava.add(List.of());
    }

    private enum Kind { TOP, PLAIN, CAPTURE, BRANCH_RESET, CONDITIONAL }

    private static final class Frame {
        final Kind kind;
        int user;
        String name;
        int resetBase;
        int resetMax;
        String negated;
        boolean seenBar;

        Frame(Kind kind) {
            this.kind = kind;
        }
    }

    private final Deque<Frame> frames = new ArrayDeque<>();

    private Compiled translate() throws PatternError {
        if (src.startsWith("(*UTF)") || src.startsWith("(*UTF8)")) {
            utf = true;
        }
        if (utf) {
            if (!PhpStrings.isUtf8(src)) {
                throw new PatternError("Compilation failed: UTF-8 error: illegal byte at offset 0");
            }
            src = PhpStrings.toJava(src);
        }
        Matcher cm = COND_REF.matcher(src);
        while (cm.find()) {
            String ref = cm.group(1);
            if (Character.isDigit(ref.charAt(0))) {
                markedNumbers.add(Integer.parseInt(ref));
            } else {
                markedNames.add(ref.replaceAll("^[<']|[>']$", ""));
            }
        }
        // Leading (*UTF8) style verbs set options; skip them before anything else.
        while (src.startsWith("(*", pos)) {
            int close = src.indexOf(')', pos);
            if (close < 0) {
                break;
            }
            String verb = src.substring(pos + 2, close);
            if (verb.equals("UTF8") || verb.equals("UTF") || verb.equals("UCP")) {
                utf = true;
            } else if (!(verb.matches("CR|LF|CRLF|ANYCRLF|ANY|NUL|BSR_ANYCRLF|BSR_UNICODE|NO_AUTO_POSSESS|NO_DOTSTAR_ANCHOR"
                    + "|NO_JIT|NO_START_OPT|NOTEMPTY|NOTEMPTY_ATSTART|LIMIT_[A-Z]+=\\d+"))) {
                break;
            }
            pos = close + 1;
        }
        frames.push(new Frame(Kind.TOP));
        try {
            sequence();
            if (pos < src.length()) {
                throw new PatternError("Compilation failed: unmatched closing parenthesis at offset " + pos);
            }
        } catch (Unsupported e) {
            throw new PatternError("Compilation failed: " + e.getMessage() + " is not supported by this runtime");
        }
        String body = resolveBackrefs(out.toString());
        if (anchored) {
            body = "\\G(?:" + body + ")";
        }
        int flags = Pattern.UNIX_LINES;
        if (caseless) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (multiline) {
            flags |= Pattern.MULTILINE;
        }
        if (dotall) {
            flags |= Pattern.DOTALL;
        }
        if (utf) {
            flags |= Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(body, flags);
        } catch (PatternSyntaxException e) {
            throw new PatternError("Compilation failed: " + e.getDescription() + " at offset " + Math.max(0, e.getIndex()));
        }
        int[][] map = new int[userGroups + 1][];
        String[] names = new String[userGroups + 1];
        map[0] = new int[0];
        for (int g = 1; g <= userGroups; g++) {
            map[g] = userToJava.get(g).stream().mapToInt(Integer::intValue).toArray();
            names[g] = userNames.get(g);
        }
        return new Compiled(pattern, userGroups, map, names, utf, src);
    }

    // ------------------------------------------------------------------ structure

    /** Reads until an unmatched ')' (left in place) or the end of the pattern. */
    private void sequence() throws PatternError {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (extended && (isSpace(c) || c == '#')) {
                skipExtended();
                continue;
            }
            switch (c) {
                case ')' -> {
                    return;
                }
                case '(' -> group();
                case '|' -> {
                    pos++;
                    alternative();
                }
                case '[' -> characterClass();
                case '\\' -> escape();
                case '*', '+', '?' -> {
                    pos++;
                    out.append(c);
                    quantifierSuffix();
                }
                case '{' -> {
                    Matcher q = QUANTIFIER.matcher(src).region(pos, src.length());
                    if (q.lookingAt()) {
                        out.append(q.group());
                        pos = q.end();
                        quantifierSuffix();
                    } else {
                        out.append("\\{");
                        pos++;
                    }
                }
                case '}' -> {
                    out.append("\\}");
                    pos++;
                }
                case '$' -> {
                    out.append(dollarEndOnly && !multiline ? "\\z" : "$");
                    pos++;
                }
                default -> {
                    out.append(c);
                    pos++;
                }
            }
        }
    }

    private void skipExtended() {
        char c = src.charAt(pos);
        if (c == '#') {
            while (pos < src.length() && src.charAt(pos) != '\n') {
                pos++;
            }
        } else {
            pos++;
        }
    }

    /** After a quantifier: PCRE's '+' (possessive) and '?' (lazy), swapped by the U modifier. */
    private void quantifierSuffix() {
        if (pos < src.length() && src.charAt(pos) == '+') {
            out.append('+');
            pos++;
            return;
        }
        boolean lazy = pos < src.length() && src.charAt(pos) == '?';
        if (lazy) {
            pos++;
        }
        if (lazy != ungreedy) {
            out.append('?');
        }
    }

    private void alternative() {
        Frame f = frames.peek();
        if (f.kind == Kind.BRANCH_RESET) {
            f.resetMax = Math.max(f.resetMax, userGroups);
            userGroups = f.resetBase;
        }
        if (f.kind == Kind.CONDITIONAL) {
            if (f.seenBar) {
                throw new Unsupported("a conditional group with more than two alternatives");
            }
            f.seenBar = true;
            out.append('|').append(f.negated);
            return;
        }
        out.append('|');
    }

    private void group() throws PatternError {
        pos++; // '('
        Frame f;
        if (src.startsWith("*", pos)) {
            verb();
            return;
        }
        if (!src.startsWith("?", pos)) {
            if (noAutoCapture) {
                f = new Frame(Kind.PLAIN);
                out.append("(?:");
            } else {
                f = openCapture(null);
            }
        } else {
            pos++; // '?'
            f = groupKind();
            if (f == null) {
                return; // comment or option setting, already fully consumed
            }
        }
        frames.push(f);
        sequence();
        if (pos >= src.length()) {
            throw new PatternError("Compilation failed: missing closing parenthesis at offset " + src.length());
        }
        pos++; // ')'
        frames.pop();
        close(f);
    }

    private Frame openCapture(String name) throws PatternError {
        Frame f = new Frame(Kind.CAPTURE);
        userGroups++;
        javaGroups++;
        while (userToJava.size() <= userGroups) {
            userToJava.add(new ArrayList<>());
        }
        userToJava.get(userGroups).add(javaGroups);
        f.user = userGroups;
        f.name = name;
        if (name != null) {
            Integer prev = nameToUser.get(name);
            if (prev != null && prev != userGroups) {
                throw new PatternError("Compilation failed: two named subpatterns have the same name (PCRE2_DUPNAMES not set) at offset " + pos);
            }
            nameToUser.put(name, userGroups);
            userNames.put(userGroups, name);
        }
        out.append('(');
        return f;
    }

    private void close(Frame f) {
        switch (f.kind) {
            case CAPTURE -> {
                if (markedNumbers.contains(f.user) || (f.name != null && markedNames.contains(f.name))) {
                    javaGroups++;
                    markers.computeIfAbsent(f.user, k -> new ArrayList<>()).add(javaGroups);
                    out.append("()");
                }
                out.append(')');
            }
            case BRANCH_RESET -> {
                userGroups = Math.max(f.resetMax, userGroups);
                out.append(')');
            }
            case CONDITIONAL -> {
                if (!f.seenBar) {
                    out.append('|').append(f.negated);
                }
                out.append(')');
            }
            default -> out.append(')');
        }
    }

    /** After "(?": decides what kind of group this is and writes its opening. */
    private Frame groupKind() throws PatternError {
        char c = peek();
        switch (c) {
            case '#' -> {
                int close = src.indexOf(')', pos);
                if (close < 0) {
                    throw new PatternError("Compilation failed: missing ) after (?# comment at offset " + src.length());
                }
                pos = close + 1;
                return null;
            }
            case ':' -> {
                pos++;
                out.append("(?:");
                return new Frame(Kind.PLAIN);
            }
            case '>', '=', '!' -> {
                pos++;
                out.append("(?").append(c);
                return new Frame(Kind.PLAIN);
            }
            case '|' -> {
                pos++;
                out.append("(?:");
                Frame f = new Frame(Kind.BRANCH_RESET);
                f.resetBase = userGroups;
                f.resetMax = userGroups;
                return f;
            }
            case '<' -> {
                char next = peek(1);
                if (next == '=' || next == '!') {
                    pos += 2;
                    out.append("(?<").append(next);
                    return new Frame(Kind.PLAIN);
                }
                pos++;
                return openCapture(readName('>'));
            }
            case '\'' -> {
                pos++;
                return openCapture(readName('\''));
            }
            case 'P' -> {
                char next = peek(1);
                if (next == '<') {
                    pos += 2;
                    return openCapture(readName('>'));
                }
                if (next == '=') {
                    pos += 2;
                    String name = readName(')');
                    backrefs.add(name);
                    out.append('\u0001').append(backrefs.size() - 1).append('\u0001');
                    return null;
                }
                throw new Unsupported("subroutine call (?P>name)");
            }
            case '(' -> {
                return conditional();
            }
            case 'R', '&', '+' -> throw new Unsupported("recursion or subroutine call (?" + c + ")");
            default -> {
                if (Character.isDigit(c) || (c == '-' && Character.isDigit(peek(1)))) {
                    throw new Unsupported("recursion or subroutine call (?" + c + ")");
                }
                return optionSetting();
            }
        }
    }

    /** (?i) (?-x) (?i:...) */
    private Frame optionSetting() throws PatternError {
        StringBuilder flags = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ')' || c == ':') {
                break;
            }
            switch (c) {
                case 'i', 'm', 's', 'x', '-' -> flags.append(c);
                case 'J', 'X' -> {
                }
                default -> throw new Unsupported("inline option '" + c + "'");
            }
            pos++;
        }
        if (pos >= src.length()) {
            throw new PatternError("Compilation failed: missing closing parenthesis at offset " + src.length());
        }
        String f = flags.toString();
        if (f.endsWith("-")) {
            f = f.substring(0, f.length() - 1);
        }
        if (src.charAt(pos) == ')') {
            pos++;
            if (!f.isEmpty()) {
                out.append("(?").append(f).append(')');
            }
            return null;
        }
        pos++; // ':'
        out.append(f.isEmpty() ? "(?:" : "(?" + f + ":");
        return new Frame(Kind.PLAIN);
    }

    /** After "(?(": writes "(?:" plus the condition, and returns a frame that adds the else branch. */
    private Frame conditional() throws PatternError {
        pos++; // '('
        Frame f = new Frame(Kind.CONDITIONAL);
        String test;
        if (peek() == '?') {
            // Assertion condition: (?(?=X)yes|no)
            pos++;
            char a = peek();
            String opener;
            if (a == '=' || a == '!') {
                opener = "(?" + a;
                pos++;
            } else if (a == '<' && (peek(1) == '=' || peek(1) == '!')) {
                opener = "(?<" + peek(1);
                pos += 2;
            } else {
                throw new Unsupported("conditional with condition (?" + a);
            }
            int groupsBefore = javaGroups;
            StringBuilder saved = new StringBuilder(out);
            out.setLength(0);
            frames.push(new Frame(Kind.PLAIN));
            sequence();
            frames.pop();
            if (pos >= src.length()) {
                throw new PatternError("Compilation failed: missing closing parenthesis at offset " + src.length());
            }
            pos++; // ')' of the assertion
            String inner = out.toString();
            out.setLength(0);
            out.append(saved);
            if (javaGroups != groupsBefore) {
                throw new Unsupported("a capturing group inside a conditional's assertion");
            }
            test = opener + inner + ")";
            f.negated = negate(opener) + inner + ")";
        } else {
            int close = src.indexOf(')', pos);
            if (close < 0) {
                throw new PatternError("Compilation failed: missing closing parenthesis at offset " + src.length());
            }
            String ref = src.substring(pos, close);
            pos = close + 1;
            if (ref.startsWith("R") || ref.equals("DEFINE") || ref.startsWith("VERSION")) {
                throw new Unsupported("conditional (?(" + ref + ")");
            }
            String name = ref.replaceAll("^[<']|[>']$", "");
            Object key = Character.isDigit(name.charAt(0)) ? (Object) Integer.parseInt(name) : name;
            backrefs.add(new MarkerRef(key));
            String set = "\u0001" + (backrefs.size() - 1) + "\u0001";
            test = "(?=" + set + ")";
            f.negated = "(?!" + set + ")";
        }
        out.append("(?:").append(test);
        return f;
    }

    private static String negate(String opener) {
        return opener.endsWith("=") ? opener.substring(0, opener.length() - 1) + "!" : opener.substring(0, opener.length() - 1) + "=";
    }

    private record MarkerRef(Object key) {
    }

    private void verb() throws PatternError {
        int close = src.indexOf(')', pos);
        if (close < 0) {
            throw new PatternError("Compilation failed: missing closing parenthesis at offset " + src.length());
        }
        String verb = src.substring(pos + 1, close);
        pos = close + 1;
        if (verb.equals("FAIL") || verb.equals("F")) {
            out.append("(?!)");
            return;
        }
        throw new Unsupported("(*" + verb + ")");
    }

    private String readName(char terminator) throws PatternError {
        int close = src.indexOf(terminator, pos);
        if (close < 0) {
            throw new PatternError("Compilation failed: syntax error in subpattern name (missing terminator?) at offset " + pos);
        }
        String name = src.substring(pos, close);
        pos = close + 1;
        if (name.isEmpty() || !name.matches("[A-Za-z_]\\w*")) {
            throw new PatternError("Compilation failed: subpattern name expected at offset " + pos);
        }
        return name;
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : 0;
    }

    private char peek(int ahead) {
        return pos + ahead < src.length() ? src.charAt(pos + ahead) : 0;
    }

    // ------------------------------------------------------------------ escapes

    private void escape() throws PatternError {
        pos++; // '\'
        if (pos >= src.length()) {
            throw new PatternError("Compilation failed: \\ at end of pattern at offset " + pos);
        }
        char c = src.charAt(pos++);
        switch (c) {
            case 'K' -> throw new Unsupported("\\K");
            case 'N' -> {
                if (peek() == '{') {
                    throw new Unsupported("\\N{...}");
                }
                out.append("[^\\n]");
            }
            case 'Q' -> {
                int e = src.indexOf("\\E", pos);
                String lit = e < 0 ? src.substring(pos) : src.substring(pos, e);
                pos = e < 0 ? src.length() : e + 2;
                if (!lit.isEmpty()) {
                    out.append(Pattern.quote(lit));
                }
            }
            case 'E' -> {
            }
            case 'g' -> groupReference();
            case 'k' -> {
                char open = peek();
                char closeCh = switch (open) {
                    case '<' -> '>';
                    case '\'' -> '\'';
                    case '{' -> '}';
                    default -> throw new PatternError("Compilation failed: \\k is not followed by a braced, angle-bracketed, or quoted name at offset " + pos);
                };
                pos++;
                backref(readName(closeCh));
            }
            case 'p', 'P' -> out.append(property(c == 'P'));
            case 'x' -> out.append(hexEscape());
            case 'o' -> {
                if (peek() != '{') {
                    throw new PatternError("Compilation failed: missing opening brace after \\o at offset " + pos);
                }
                int close = src.indexOf('}', pos);
                int v = Integer.parseInt(src.substring(pos + 1, close), 8);
                pos = close + 1;
                out.append("\\x{").append(Integer.toHexString(v)).append('}');
            }
            case '0' -> out.append(octalAt(pos - 1));
            default -> {
                if (c >= '1' && c <= '9') {
                    int start = pos - 1;
                    while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                        pos++;
                    }
                    int num = Integer.parseInt(src.substring(start, pos));
                    if (num < 10 || num <= userGroups) {
                        backref(num);
                    } else {
                        out.append(octalAt(start));
                    }
                } else {
                    out.append('\\').append(c);
                }
            }
        }
    }

    /** \g1 \g{1} \g{-1} \g{name}; \g<..> and \g'..' are subroutine calls. */
    private void groupReference() throws PatternError {
        char c = peek();
        if (c == '<' || c == '\'') {
            throw new Unsupported("subroutine call \\g" + c);
        }
        String ref;
        if (c == '{') {
            int close = src.indexOf('}', pos);
            if (close < 0) {
                throw new PatternError("Compilation failed: missing closing brace in \\g{ at offset " + pos);
            }
            ref = src.substring(pos + 1, close);
            pos = close + 1;
        } else {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            ref = src.substring(start, pos);
        }
        if (ref.matches("-\\d+")) {
            backref(userGroups + 1 + Integer.parseInt(ref));
        } else if (ref.matches("\\+?\\d+")) {
            backref(Integer.parseInt(ref.replace("+", "")));
        } else if (ref.matches("[A-Za-z_]\\w*")) {
            backref(ref);
        } else {
            throw new PatternError("Compilation failed: a numbered reference must not be zero at offset " + pos);
        }
    }

    private void backref(Object key) {
        backrefs.add(key);
        out.append('\u0001').append(backrefs.size() - 1).append('\u0001');
    }

    private String resolveBackrefs(String body) throws PatternError {
        if (backrefs.isEmpty()) {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c != '\u0001') {
                sb.append(c);
                i++;
                continue;
            }
            int end = body.indexOf('\u0001', i + 1);
            Object ref = backrefs.get(Integer.parseInt(body.substring(i + 1, end)));
            i = end + 1;
            boolean marker = ref instanceof MarkerRef;
            Object key = marker ? ((MarkerRef) ref).key() : ref;
            int user;
            if (key instanceof Integer n) {
                user = n;
            } else {
                Integer u = nameToUser.get((String) key);
                if (u == null) {
                    throw new PatternError("Compilation failed: reference to non-existent subpattern at offset " + i);
                }
                user = u;
            }
            if (user < 1 || user > userGroups) {
                throw new PatternError("Compilation failed: reference to non-existent subpattern at offset " + i);
            }
            List<Integer> targets = marker ? markers.getOrDefault(user, List.of()) : userToJava.get(user);
            if (targets.isEmpty()) {
                throw new Unsupported("a condition on a group that is defined after it");
            }
            sb.append("(?:");
            for (int k = 0; k < targets.size(); k++) {
                if (k > 0) {
                    sb.append('|');
                }
                sb.append('\\').append(targets.get(k));
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private String hexEscape() {
        if (peek() == '{') {
            int close = src.indexOf('}', pos);
            if (close < 0) {
                return "\\x{0}\\{";
            }
            String hex = src.substring(pos + 1, close);
            pos = close + 1;
            return "\\x{" + hex + "}";
        }
        int start = pos;
        while (pos < src.length() && pos - start < 2 && isHex(src.charAt(pos))) {
            pos++;
        }
        String hex = src.substring(start, pos);
        return "\\x{" + (hex.isEmpty() ? "0" : hex) + "}";
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** An octal escape of up to three digits at {@code start}; a non-octal digit stands for itself. */
    private String octalAt(int start) {
        int end = start;
        while (end < src.length() && end - start < 3 && src.charAt(end) >= '0' && src.charAt(end) <= '7') {
            end++;
        }
        if (end == start) {
            pos = start + 1;
            return String.valueOf(src.charAt(start));
        }
        int v = Integer.parseInt(src.substring(start, end), 8);
        pos = end;
        return "\\x{" + Integer.toHexString(v) + "}";
    }

    private String property(boolean negated) throws PatternError {
        String name;
        if (peek() == '{') {
            int close = src.indexOf('}', pos);
            if (close < 0) {
                throw new PatternError("Compilation failed: malformed \\P or \\p sequence at offset " + pos);
            }
            name = src.substring(pos + 1, close);
            pos = close + 1;
        } else {
            name = String.valueOf(src.charAt(pos++));
        }
        if (name.startsWith("^")) {
            negated = !negated;
            name = name.substring(1);
        }
        String p = negated ? "\\P" : "\\p";
        if (GENERAL_CATEGORIES.contains(name)) {
            return p + "{" + name + "}";
        }
        if (name.equals("L&") || name.equals("LC")) {
            return negated ? "[^\\p{Lu}\\p{Ll}\\p{Lt}]" : "[\\p{Lu}\\p{Ll}\\p{Lt}]";
        }
        if (name.equals("Any")) {
            return negated ? "(?!)" : "[\\s\\S]";
        }
        if (name.startsWith("X")) {
            throw new Unsupported("\\p{" + name + "}");
        }
        return p + "{Is" + name + "}";
    }

    // ------------------------------------------------------------------ classes

    private static final Map<String, String> POSIX = Map.ofEntries(
            Map.entry("alpha", "Alpha"), Map.entry("digit", "Digit"), Map.entry("alnum", "Alnum"),
            Map.entry("upper", "Upper"), Map.entry("lower", "Lower"), Map.entry("space", "Space"),
            Map.entry("punct", "Punct"), Map.entry("xdigit", "XDigit"), Map.entry("cntrl", "Cntrl"),
            Map.entry("graph", "Graph"), Map.entry("print", "Print"), Map.entry("blank", "Blank"),
            Map.entry("ascii", "ASCII"));

    private void characterClass() throws PatternError {
        int open = pos;
        pos++; // '['
        out.append('[');
        if (peek() == '^') {
            out.append('^');
            pos++;
        }
        if (peek() == ']') {
            out.append("\\]");
            pos++;
        }
        boolean lastWasClassEscape = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ']') {
                out.append(']');
                pos++;
                return;
            }
            boolean classEscape = false;
            if (c == '[' && peek(1) == ':') {
                int close = src.indexOf(":]", pos + 2);
                if (close > 0) {
                    String name = src.substring(pos + 2, close);
                    boolean neg = name.startsWith("^");
                    if (neg) {
                        name = name.substring(1);
                    }
                    if (name.equals("word")) {
                        out.append(neg ? "\\W" : "\\w");
                    } else {
                        String java = POSIX.get(name);
                        if (java == null) {
                            throw new PatternError("Compilation failed: unknown POSIX class name at offset " + pos);
                        }
                        out.append(neg ? "\\P{" : "\\p{").append(java).append('}');
                    }
                    pos = close + 2;
                    lastWasClassEscape = true;
                    continue;
                }
            }
            switch (c) {
                case '[' -> {
                    out.append("\\[");
                    pos++;
                }
                case '&' -> {
                    out.append("\\&");
                    pos++;
                }
                case '-' -> {
                    out.append(lastWasClassEscape && peek(1) != ']' ? "\\-" : "-");
                    pos++;
                }
                case '\\' -> classEscape = classEscape();
                default -> {
                    out.append(c);
                    pos++;
                }
            }
            lastWasClassEscape = classEscape;
        }
        throw new PatternError("Compilation failed: missing terminating ] for character class at offset " + src.length() + " (class at " + open + ")");
    }

    /** Returns true when the escape is itself a class (\d, \w, \p{..}), after which '-' is literal. */
    private boolean classEscape() throws PatternError {
        pos++;
        if (pos >= src.length()) {
            throw new PatternError("Compilation failed: \\ at end of pattern at offset " + pos);
        }
        char c = src.charAt(pos++);
        switch (c) {
            case 'd', 'D', 'w', 'W', 's', 'S', 'h', 'H', 'v', 'V' -> {
                out.append('\\').append(c);
                return true;
            }
            case 'p', 'P' -> {
                out.append(property(c == 'P'));
                return true;
            }
            case 'b' -> out.append("\\x{8}");
            case 'x' -> out.append(hexEscape());
            case 'Q' -> {
                int e = src.indexOf("\\E", pos);
                String lit = e < 0 ? src.substring(pos) : src.substring(pos, e);
                pos = e < 0 ? src.length() : e + 2;
                for (int i = 0; i < lit.length(); i++) {
                    char ch = lit.charAt(i);
                    out.append(Character.isLetterOrDigit(ch) ? String.valueOf(ch) : "\\" + ch);
                }
            }
            case 'E' -> {
            }
            default -> {
                if (c >= '0' && c <= '7') {
                    out.append(octalAt(pos - 1));
                } else {
                    out.append('\\').append(c);
                }
            }
        }
        return false;
    }
}
