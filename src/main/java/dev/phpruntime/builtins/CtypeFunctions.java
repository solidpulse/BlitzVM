package dev.phpruntime.builtins;

import java.util.function.IntPredicate;

/** ctype_* in the ASCII C locale. */
final class CtypeFunctions {

    private CtypeFunctions() {
    }

    static void register() {
        ctype("ctype_digit", c -> c >= '0' && c <= '9');
        ctype("ctype_alpha", CtypeFunctions::alpha);
        ctype("ctype_alnum", c -> alpha(c) || (c >= '0' && c <= '9'));
        ctype("ctype_upper", c -> c >= 'A' && c <= 'Z');
        ctype("ctype_lower", c -> c >= 'a' && c <= 'z');
        ctype("ctype_space", c -> c == ' ' || (c >= '\t' && c <= '\r'));
        ctype("ctype_xdigit", c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
        ctype("ctype_punct", c -> c > ' ' && c < 127 && !alpha(c) && !(c >= '0' && c <= '9'));
        ctype("ctype_print", c -> c >= ' ' && c < 127);
        ctype("ctype_graph", c -> c > ' ' && c < 127);
        ctype("ctype_cntrl", c -> c < ' ' || c == 127);
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
