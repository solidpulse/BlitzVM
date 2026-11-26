package dev.phpruntime.builtins;

import java.util.function.IntPredicate;

/** ctype_* in the ASCII C locale. */
final class CtypeFunctions {

    private CtypeFunctions() {
    }

    static final IntPredicate DIGIT = c -> c >= '0' && c <= '9';
    static final IntPredicate ALPHA = CtypeFunctions::alpha;
    static final IntPredicate ALNUM = c -> alpha(c) || DIGIT.test(c);
    static final IntPredicate UPPER = c -> c >= 'A' && c <= 'Z';
    static final IntPredicate LOWER = c -> c >= 'a' && c <= 'z';
    static final IntPredicate SPACE = c -> c == ' ' || (c >= '\t' && c <= '\r');
    static final IntPredicate XDIGIT = c -> DIGIT.test(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    static final IntPredicate PUNCT = c -> c > ' ' && c < 127 && !alpha(c) && !DIGIT.test(c);
    static final IntPredicate PRINT = c -> c >= ' ' && c < 127;
    static final IntPredicate GRAPH = c -> c > ' ' && c < 127;
    static final IntPredicate CNTRL = c -> c < ' ' || c == 127;

    static void register() {
        ctype("ctype_digit", DIGIT);
        ctype("ctype_alpha", ALPHA);
        ctype("ctype_alnum", ALNUM);
        ctype("ctype_upper", UPPER);
        ctype("ctype_lower", LOWER);
        ctype("ctype_space", SPACE);
        ctype("ctype_xdigit", XDIGIT);
        ctype("ctype_punct", PUNCT);
        ctype("ctype_print", PRINT);
        ctype("ctype_graph", GRAPH);
        ctype("ctype_cntrl", CNTRL);
        // mbstring is registered by MbConvertFunctions, at its explicit charset boundary.
    }

    private static boolean alpha(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Strings are tested character by character (non-ASCII never matches, as in the C locale).
     * Integers -128..255 are treated as one character, other integers as their digits.
     */
    private static void ctype(String name, IntPredicate test) {
        Builtins.def(name, 1, 1, (ctx, a) -> {
            String s;
            if (a[0] instanceof String str) {
                s = str;
            } else if (a[0] instanceof Long l) {
                int line = dev.phpruntime.runtime.PhpBacktrace.callSiteLine(ctx.currentBuiltinCallSite());
                ctx.userError(8192L, name + "(): Argument of type int will be interpreted as string in the future", line);
                if (l >= -128 && l <= 255) {
                    return test.test((int) (l < 0 ? l + 256 : l));
                }
                s = Long.toString(l);
            } else {
                return false;
            }
            if (s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!test.test(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        });
    }

}
