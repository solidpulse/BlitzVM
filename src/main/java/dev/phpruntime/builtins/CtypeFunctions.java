package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.util.Map;
import java.util.function.IntPredicate;

/** ctype_* (C locale) and the basic mbstring functions WordPress calls during boot. */
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
        mbstring();
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

    private static void mbstring() {
        Builtins.def("mb_internal_encoding", 0, 1, (ctx, a) -> {
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                return "UTF-8";
            }
            String enc = PhpOps.toStr(a[0]);
            if (!knownEncoding(enc)) {
                throw new PhpError("mb_internal_encoding(): Argument #1 ($encoding) must be a valid encoding, \"" + enc + "\" given");
            }
            return true;
        });
        Builtins.def("mb_check_encoding", 0, 2, (ctx, a) -> a.length == 0 || checkEncoding(a[0]));
        Builtins.def("mb_ord", 1, 2, (ctx, a) -> {
            String s = PhpOps.toStr(a[0]);
            if (s.isEmpty()) {
                throw new PhpError("mb_ord(): Argument #1 ($string) must not be empty");
            }
            return (long) s.codePointAt(0);
        });
        Builtins.def("mb_chr", 1, 2, (ctx, a) -> {
            long cp = PhpOps.toLong(a[0]);
            if (cp < 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                return false;
            }
            return new String(Character.toChars((int) cp));
        });
        Builtins.def("mb_substitute_character", 0, 1, (ctx, a) -> a.length == 0 || a[0] == PhpNull.INSTANCE ? (Object) 63L : (Object) true);
        Builtins.def("mb_detect_order", 0, 1, (ctx, a) -> {
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                PhpArray order = new PhpArray();
                order.append("ASCII");
                order.append("UTF-8");
                return order;
            }
            return true;
        });
    }

    private static boolean knownEncoding(String enc) {
        return switch (enc.toUpperCase().replace("_", "-")) {
            case "UTF-8", "UTF8", "ASCII", "US-ASCII", "ISO-8859-1", "LATIN1", "8BIT", "BINARY", "UTF-16", "UTF-16BE",
                 "UTF-16LE", "UTF-32", "WINDOWS-1252", "CP1252", "HTML-ENTITIES", "BASE64" -> true;
            default -> false;
        };
    }

    /** Java strings are already decoded; only unpaired surrogates make one invalid UTF-8. */
    private static boolean checkEncoding(Object v) {
        if (v instanceof PhpArray arr) {
            for (Map.Entry<Object, Object> e : arr.entries()) {
                if (!checkEncoding(e.getKey()) || !checkEncoding(e.getValue())) {
                    return false;
                }
            }
            return true;
        }
        String s = PhpOps.toStr(v);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
