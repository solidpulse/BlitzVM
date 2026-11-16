package dev.phpruntime.builtins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands PCRE recursion and subroutine calls ({@code (?R)}, {@code (?1)}, {@code (?-1)},
 * {@code (?+1)}, {@code (?&name)}, {@code (?P>name)}, {@code \g<name>}) into inline copies of the
 * called group, since java.util.regex has neither. Copies are non-capturing (PCRE2 restores the
 * captures after a subroutine call) and recursion is cut off after at most {@link #MAX_DEPTH} levels with
 * a never-matching {@code (?!)}, so nesting deeper than that does not match. PCRE2 calls are not
 * atomic, so plain inlining also keeps backtracking behaviour.
 *
 * <p>Works on the PCRE source before translation. Returns null when the pattern has no calls.
 */
final class PcreSubroutines {
    static final int MAX_DEPTH = 24;
    private static final int MAX_LENGTH = 200_000;

    private record Event(int pos, int len, int capture, int call, String callName) {
    }

    private final String src;
    private final List<Event> events = new ArrayList<>();
    private final List<int[]> groups = new ArrayList<>(); // [open, bodyStart, close]
    private final Map<String, Integer> names = new HashMap<>();
    private boolean hasCall;

    private PcreSubroutines(String src) {
        this.src = src;
        groups.add(new int[]{-1, 0, src.length()});
    }

    static String expand(String src, boolean extended, boolean noAutoCapture) {
        if (src.indexOf("(?") < 0 && src.indexOf("\\g") < 0) {
            return null;
        }
        PcreSubroutines s = new PcreSubroutines(src);
        if (!s.scan(extended, noAutoCapture) || !s.hasCall) {
            return null;
        }
        // Recursion that branches (two self-calls per level) grows exponentially: take the
        // deepest cut-off that stays under the size limit.
        for (int depth = MAX_DEPTH; depth >= 2; depth -= 2) {
            StringBuilder out = new StringBuilder();
            if (s.emit(out, 0, src.length(), 0, false, depth)) {
                return out.toString();
            }
        }
        return null;
    }

    /** Records captures and calls; false when the pattern uses something this expansion cannot model. */
    private boolean scan(boolean extended, boolean noAutoCapture) {
        Deque<Integer> stack = new ArrayDeque<>(); // group number, or -1 for a non-capturing paren
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                if (i + 1 < n && src.charAt(i + 1) == 'Q') {
                    int e = src.indexOf("\\E", i + 2);
                    i = e < 0 ? n : e + 2;
                    continue;
                }
                if (i + 2 < n && src.charAt(i + 1) == 'g' && (src.charAt(i + 2) == '<' || src.charAt(i + 2) == '\'')) {
                    char close = src.charAt(i + 2) == '<' ? '>' : '\'';
                    int e = src.indexOf(close, i + 3);
                    if (e < 0) {
                        return false;
                    }
                    if (!addCall(i, e + 1 - i, src.substring(i + 3, e))) {
                        return false;
                    }
                    i = e + 1;
                    continue;
                }
                i += 2;
                continue;
            }
            if (c == '[') {
                i = skipClass(i);
                continue;
            }
            if (extended && c == '#') {
                int e = src.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
                continue;
            }
            if (c == ')') {
                if (stack.isEmpty()) {
                    return false;
                }
                int g = stack.pop();
                if (g > 0) {
                    groups.get(g)[2] = i;
                }
                i++;
                continue;
            }
            if (c != '(') {
                i++;
                continue;
            }
            if (i + 1 >= n || src.charAt(i + 1) != '?') {
                if (i + 1 < n && src.charAt(i + 1) == '*') { // (*VERB)
                    int e = src.indexOf(')', i);
                    i = e < 0 ? n : e + 1;
                    continue;
                }
                if (noAutoCapture) {
                    stack.push(-1);
                } else {
                    stack.push(openCapture(i, 1, null));
                }
                i++;
                continue;
            }
            char d = i + 2 < n ? src.charAt(i + 2) : 0;
            char d2 = i + 3 < n ? src.charAt(i + 3) : 0;
            if (d == '#') {
                int e = src.indexOf(')', i);
                i = e < 0 ? n : e + 1;
                continue;
            }
            if (d == '|') {
                return false; // branch reset renumbers groups
            }
            if ((d == '<' && d2 != '=' && d2 != '!') || d == '\'' || (d == 'P' && d2 == '<')) {
                int nameStart = i + (d == 'P' ? 4 : 3);
                int e = src.indexOf(d == '\'' ? '\'' : '>', nameStart);
                if (e < 0) {
                    return false;
                }
                stack.push(openCapture(i, e + 1 - i, src.substring(nameStart, e)));
                i = e + 1;
                continue;
            }
            if (d == 'R' || d == '&' || (d == 'P' && d2 == '>') || Character.isDigit(d)
                    || ((d == '-' || d == '+') && Character.isDigit(d2))) {
                int e = src.indexOf(')', i);
                if (e < 0) {
                    return false;
                }
                String ref = src.substring(i + 2, e);
                if (ref.startsWith("&")) {
                    ref = ref.substring(1);
                } else if (ref.startsWith("P>")) {
                    ref = ref.substring(2);
                }
                if (!addCall(i, e + 1 - i, ref)) {
                    return false;
                }
                i = e + 1;
                continue;
            }
            if (d == '(') {
                // Conditional: (?(1)...), (?(<name>)...), or (?(?=...)...). Skip a reference condition.
                stack.push(-1);
                if (d2 == '?' || d2 == '*') {
                    i += 2;
                } else {
                    if (d2 == 'R') {
                        return false; // recursion conditions need real recursion
                    }
                    int e = src.indexOf(')', i + 3);
                    i = e < 0 ? n : e + 1;
                }
                continue;
            }
            stack.push(-1);
            i += 2;
        }
        if (!stack.isEmpty()) {
            return false;
        }
        for (int k = 0; k < events.size(); k++) {
            Event ev = events.get(k);
            if (ev.callName != null) {
                Integer g = names.get(ev.callName);
                if (g == null) {
                    return false;
                }
                events.set(k, new Event(ev.pos, ev.len, 0, g, null));
            }
        }
        for (Event ev : events) {
            if (ev.call >= groups.size()) {
                return false;
            }
        }
        return true;
    }

    private int openCapture(int pos, int len, String name) {
        int g = groups.size();
        groups.add(new int[]{pos, pos + len, -1});
        if (name != null) {
            names.put(name, g);
        }
        events.add(new Event(pos, len, g, -1, null));
        return g;
    }

    private boolean addCall(int pos, int len, String ref) {
        hasCall = true;
        int opened = groups.size() - 1;
        int target;
        if (ref.equals("R") || ref.equals("0")) {
            target = 0;
        } else if (ref.matches("[+-]\\d+")) {
            int rel = Integer.parseInt(ref.substring(1));
            target = ref.charAt(0) == '-' ? opened - rel + 1 : opened + rel;
            if (target <= 0) {
                return false;
            }
        } else if (ref.matches("\\d+")) {
            target = Integer.parseInt(ref);
        } else if (ref.matches("[A-Za-z_]\\w*")) {
            events.add(new Event(pos, len, 0, -1, ref));
            return true;
        } else {
            return false;
        }
        events.add(new Event(pos, len, 0, target, null));
        return true;
    }

    private int skipClass(int i) {
        int n = src.length();
        int p = i + 1;
        if (p < n && src.charAt(p) == '^') {
            p++;
        }
        if (p < n && src.charAt(p) == ']') {
            p++;
        }
        while (p < n) {
            char c = src.charAt(p);
            if (c == '\\') {
                p += 2;
            } else if (c == '[' && p + 1 < n && src.charAt(p + 1) == ':') {
                int e = src.indexOf(":]", p + 2);
                p = e < 0 ? p + 1 : e + 2;
            } else if (c == ']') {
                return p + 1;
            } else {
                p++;
            }
        }
        return n;
    }

    /**
     * Copies src[from, to) to out. Inside an inlined copy ({@code inline}), captures become
     * non-capturing; every call is replaced by the called group's body, one level deeper.
     */
    private boolean emit(StringBuilder out, int from, int to, int depth, boolean inline, int maxDepth) {
        int p = from;
        for (Event ev : events) {
            if (ev.pos < from || ev.pos >= to) {
                continue;
            }
            out.append(src, p, ev.pos);
            p = ev.pos + ev.len;
            if (ev.capture > 0) {
                out.append(inline ? "(?:" : src.substring(ev.pos, p));
                continue;
            }
            if (depth >= maxDepth) {
                out.append("(?!)");
                continue;
            }
            int[] g = groups.get(ev.call);
            out.append("(?:");
            if (!emit(out, g[1], g[2], depth + 1, true, maxDepth)) {
                return false;
            }
            out.append(')');
            if (out.length() > MAX_LENGTH) {
                return false;
            }
        }
        out.append(src, p, to);
        return true;
    }
}
