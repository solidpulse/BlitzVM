package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unicode-aware PHP functions. Decode only here, operate on code points, and encode
 * back to PHP bytes. All state (encoding, substitute, detect order) is request-local.
 */
final class MbConvertFunctions {

    private MbConvertFunctions() {
    }

    /** A supported encoding: the name mbstring reports and the Java charset behind it. */
    record Encoding(String name, Charset charset) {
        boolean singleByte() {
            return charset != null && (charset.equals(StandardCharsets.ISO_8859_1) || charset.name().equals("windows-1252")
                    || charset.equals(StandardCharsets.US_ASCII));
        }
    }

    static Encoding encoding(String name) {
        String n = PhpStrings.upper(name.trim()).replace('_', '-');
        return switch (n) {
            case "UTF-8", "UTF8" -> new Encoding("UTF-8", StandardCharsets.UTF_8);
            case "ASCII", "US-ASCII" -> new Encoding("ASCII", StandardCharsets.US_ASCII);
            case "ISO-8859-1", "ISO8859-1", "LATIN1", "LATIN-1", "8BIT", "BINARY" -> new Encoding("ISO-8859-1", StandardCharsets.ISO_8859_1);
            case "WINDOWS-1252", "CP1252" -> new Encoding("Windows-1252", Charset.forName("windows-1252"));
            case "UTF-16", "UTF-16BE" -> new Encoding(n, StandardCharsets.UTF_16BE);
            case "UTF-16LE" -> new Encoding("UTF-16LE", StandardCharsets.UTF_16LE);
            case "UTF-32", "UTF-32BE" -> new Encoding(n, Charset.forName("UTF-32BE"));
            case "UTF-32LE" -> new Encoding("UTF-32LE", Charset.forName("UTF-32LE"));
            case "HTML-ENTITIES" -> new Encoding("HTML-ENTITIES", null);
            default -> null;
        };
    }

    static void register() {
        settings();
        strings();
        iconv();
        Builtins.def("mb_convert_encoding", 2, 3, (ctx, a) -> {
            Encoding to = encoding(PhpOps.toStr(a[1]));
            if (to == null) {
                throw new PhpError("ValueError", "mb_convert_encoding(): Argument #2 ($to_encoding) must be a valid encoding, \""
                        + PhpOps.toStr(a[1]) + "\" given", null);
            }
            List<Encoding> from = a.length < 3 || a[2] == PhpNull.INSTANCE
                    ? List.of(require(ContextState.of(ctx).mbEncoding, "mb_convert_encoding", 3))
                    : encodings(a[2], "mb_convert_encoding", 3);
            return convertValue(ctx, a[0], to, from);
        });
        Builtins.def("mb_scrub", 1, 2, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 1, "mb_scrub");
            return encode(ctx, decode(ctx, PhpOps.toStr(a[0]), e), e);
        });
        Builtins.def("mb_detect_encoding", 1, 3, (ctx, a) -> {
            String s = PhpOps.toStr(a[0]);
            List<Encoding> candidates = a.length < 2 || a[1] == PhpNull.INSTANCE
                    ? encodings(PhpArray.ofList(ContextState.of(ctx).mbDetectOrder), "mb_detect_encoding", 2)
                    : encodings(a[1], "mb_detect_encoding", 2);
            Encoding found = detect(s, candidates);
            return found == null ? false : found.name();
        });
    }

    private static Object convertValue(dev.phpruntime.PhpContext ctx, Object v, Encoding to, List<Encoding> from) {
        if (v instanceof PhpArray arr) {
            PhpArray out = new PhpArray();
            for (var e : arr.entries()) {
                Object key = e.getKey() instanceof String k ? convert(ctx, k, to, from) : e.getKey();
                out.put(key, convertValue(ctx, e.getValue(), to, from));
            }
            return out;
        }
        return convert(ctx, PhpOps.toStr(v), to, from);
    }

    private static String convert(dev.phpruntime.PhpContext ctx, String s, Encoding to, List<Encoding> from) {
        Encoding source = from.size() == 1 ? from.get(0) : detect(s, from);
        if (source == null) {
            source = from.get(0);
        }
        return encode(ctx, decode(ctx, s, source), to);
    }

    /** First candidate the string is valid in: ASCII for pure ASCII, UTF-8 for valid Unicode text, else single-byte. */
    private static Encoding detect(String s, List<Encoding> candidates) {
        boolean ascii = s.chars().allMatch(c -> c < 0x80);
        for (Encoding e : candidates) {
            if (e.charset() == null) {
                continue;
            }
            if (e.charset().equals(StandardCharsets.US_ASCII)) {
                if (ascii) {
                    return e;
                }
            } else if (e.charset().equals(StandardCharsets.UTF_8)) {
                if (PhpStrings.isUtf8(s)) {
                    return e;
                }
            } else if (e.singleByte()) {
                if (PhpStrings.isEncoding(s, e.charset())) {
                    return e;
                }
            } else if (PhpStrings.isEncoding(s, e.charset())) {
                return e;
            }
        }
        return null;
    }

    /** Encoding list argument: null (detect order), a comma-separated string or an array. */
    private static List<Encoding> encodings(Object v, String fn, int argNo) {
        List<String> names = new ArrayList<>();
        if (v == PhpNull.INSTANCE) {
            names.add("ASCII");
            names.add("UTF-8");
        } else if (v instanceof PhpArray arr) {
            for (Object x : arr.values()) {
                names.add(PhpOps.toStr(x));
            }
        } else {
            String s = PhpOps.toStr(v);
            if (s.isEmpty() || s.equalsIgnoreCase("auto")) {
                names.add("ASCII");
                names.add("UTF-8");
            } else {
                for (String part : s.split(",")) {
                    names.add(part.trim());
                }
            }
        }
        List<Encoding> out = new ArrayList<>();
        for (String n : names) {
            Encoding e = encoding(n);
            if (e == null) {
                throw new PhpError("ValueError", fn + "(): Argument #" + argNo + " ($" + (argNo == 3 ? "from_encoding" : "encodings")
                        + ") contains invalid encoding \"" + n + "\"", null);
            }
            out.add(e);
        }
        return out;
    }

    private static Encoding require(String name, String fn, int arg) {
        Encoding e = encoding(name);
        if (e == null) {
            throw new PhpError(PhpError.VALUE_ERROR, fn + "(): Argument #" + arg + " ($encoding) must be a valid encoding, \"" + name + "\" given", null);
        }
        return e;
    }

    private static Encoding argumentEncoding(dev.phpruntime.PhpContext ctx, Object[] a, int i, String fn) {
        return require(a.length > i && a[i] != PhpNull.INSTANCE ? PhpOps.toStr(a[i]) : ContextState.of(ctx).mbEncoding, fn, i + 1);
    }

    private static String substitute(dev.phpruntime.PhpContext ctx) {
        Object value = ContextState.of(ctx).mbSubstitute;
        return value instanceof Long cp ? new String(Character.toChars(cp.intValue())) : value.equals("none") ? "" : "?";
    }

    private static String decode(dev.phpruntime.PhpContext ctx, String s, Encoding e) {
        if (e.charset() == null) {
            return PhpStrings.toJava(HtmlEntities.decodeAll(s, HtmlEntities.DEFAULT_FLAGS));
        }
        // mbstring's generic UTF-16 input honors a BOM but its output is BE without one.
        Charset charset = e.name().equals("UTF-16") ? StandardCharsets.UTF_16 : e.charset();
        return PhpStrings.decode(s, charset, substitute(ctx));
    }

    private static String encode(dev.phpruntime.PhpContext ctx, String text, Encoding e) {
        if (e.charset() == null) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i);
                if (cp < 128) {
                    out.append((char) cp);
                } else {
                    String name = HtmlEntities.name(cp);
                    out.append(name == null ? "&#" + cp + ";" : "&" + name + ";");
                }
                i += Character.charCount(cp);
            }
            return out.toString();
        }
        return PhpStrings.encode(text, e.charset(), substitute(ctx));
    }

    private static void settings() {
        Builtins.def("mb_internal_encoding", 0, 1, (ctx, a) -> {
            ContextState st = ContextState.of(ctx);
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                return st.mbEncoding;
            }
            st.mbEncoding = require(PhpOps.toStr(a[0]), "mb_internal_encoding", 1).name();
            return true;
        });
        Builtins.def("mb_substitute_character", 0, 1, (ctx, a) -> {
            ContextState st = ContextState.of(ctx);
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                return st.mbSubstitute;
            }
            Object value = a[0];
            if (value instanceof String s && List.of("none", "long", "entity").contains(PhpStrings.lower(s))) {
                st.mbSubstitute = PhpStrings.lower(s);
            } else {
                long cp = PhpOps.toLong(value);
                if (cp < 0 || cp > 0x10FFFF || cp >= 0xD800 && cp <= 0xDFFF) {
                    throw new PhpError(PhpError.VALUE_ERROR, "mb_substitute_character(): Argument #1 ($substitute_character) is not a valid codepoint", null);
                }
                st.mbSubstitute = cp;
            }
            return true;
        });
        Builtins.def("mb_detect_order", 0, 1, (ctx, a) -> {
            ContextState st = ContextState.of(ctx);
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                return PhpArray.ofList(st.mbDetectOrder);
            }
            st.mbDetectOrder = encodings(a[0], "mb_detect_order", 1).stream().map(Encoding::name).toList();
            return true;
        });
        Builtins.def("mb_list_encodings", 0, 0, (ctx, a) -> PhpArray.ofList(List.of(
                "ASCII", "UTF-8", "ISO-8859-1", "Windows-1252", "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32", "UTF-32LE", "UTF-32BE", "HTML-ENTITIES")));
        Builtins.def("mb_check_encoding", 0, 2, (ctx, a) -> a.length == 0 || check(a[0], argumentEncoding(ctx, a, 1, "mb_check_encoding")));
    }

    private static boolean check(Object value, Encoding e) {
        if (value instanceof PhpArray arr) {
            for (var pair : arr.entries()) {
                if (!check(pair.getKey(), e) || !check(pair.getValue(), e)) {
                    return false;
                }
            }
            return true;
        }
        return e.charset() == null || PhpStrings.isEncoding(PhpOps.toStr(value), e.charset());
    }

    private static void strings() {
        Builtins.def("mb_strlen", 1, 2, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 1, "mb_strlen");
            String s = PhpOps.toStr(a[0]);
            if (StandardCharsets.UTF_8.equals(e.charset())) {
                return (long) PhpStrings.utf8Length(s);
            }
            String text = decode(ctx, s, e);
            return (long) text.codePointCount(0, text.length());
        });
        Builtins.def("mb_substr", 2, 4, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 3, "mb_substr");
            if (StandardCharsets.UTF_8.equals(e.charset())) {
                return PhpStrings.utf8Substr(PhpOps.toStr(a[0]), PhpOps.toLong(a[1]),
                        a.length > 2 && a[2] != PhpNull.INSTANCE ? PhpOps.toLong(a[2]) : null, substitute(ctx));
            }
            String text = decode(ctx, PhpOps.toStr(a[0]), e);
            return encode(ctx, cpSubstr(text, PhpOps.toLong(a[1]), a.length > 2 && a[2] != PhpNull.INSTANCE ? PhpOps.toLong(a[2]) : null), e);
        });
        Builtins.def("mb_convert_case", 2, 3, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 2, "mb_convert_case");
            String text = decode(ctx, PhpOps.toStr(a[0]), e);
            long mode = PhpOps.toLong(a[1]);
            if (mode < 0 || mode > 7) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "mb_convert_case(): Argument #2 ($mode) must be one of the MB_CASE_* constants", null);
            }
            String converted = switch ((int) (mode & 3)) {
                case 0 -> text.toUpperCase(Locale.ROOT);
                case 1, 3 -> text.toLowerCase(Locale.ROOT);
                case 2 -> {
                    StringBuilder out = new StringBuilder(text.length());
                    boolean start = true;
                    for (int i = 0; i < text.length(); ) {
                        int cp = text.codePointAt(i);
                        int len = Character.charCount(cp);
                        if (Character.isLetter(cp)) {
                            out.appendCodePoint(start ? Character.toTitleCase(cp) : Character.toLowerCase(cp));
                            start = false;
                        } else {
                            out.appendCodePoint(cp);
                            start = true;
                        }
                        i += len;
                    }
                    yield out.toString();
                }
                default -> text;
            };
            return encode(ctx, converted, e);
        });
        Builtins.def("mb_strtoupper", 1, 2, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 1, "mb_strtoupper");
            return encode(ctx, decode(ctx, PhpOps.toStr(a[0]), e).toUpperCase(Locale.ROOT), e);
        });
        Builtins.def("mb_strtolower", 1, 2, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 1, "mb_strtolower");
            return encode(ctx, decode(ctx, PhpOps.toStr(a[0]), e).toLowerCase(Locale.ROOT), e);
        });
        Builtins.def("mb_ord", 1, 2, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 1, "mb_ord");
            String s = PhpOps.toStr(a[0]);
            if (s.isEmpty()) {
                return false;
            }
            if (StandardCharsets.UTF_8.equals(e.charset()) && PhpStrings.utf8SequenceLength(s, 0) == 0) {
                return false;
            }
            return (long) decode(ctx, s, e).codePointAt(0);
        });
        Builtins.def("mb_chr", 1, 2, (ctx, a) -> {
            long cp = PhpOps.toLong(a[0]);
            Encoding e = argumentEncoding(ctx, a, 1, "mb_chr");
            if (cp < 0 || cp > 0x10FFFF || cp >= 0xD800 && cp <= 0xDFFF) {
                return false;
            }
            String text = new String(Character.toChars((int) cp));
            if (e.charset() != null && !PhpStrings.canEncode(text, e.charset())) {
                return false;
            }
            return encode(ctx, text, e);
        });
        Builtins.def("mb_strpos", 2, 4, (ctx, a) -> position(ctx, a, false, false));
        Builtins.def("mb_strrpos", 2, 4, (ctx, a) -> position(ctx, a, true, false));
        Builtins.def("mb_stripos", 2, 4, (ctx, a) -> position(ctx, a, false, true));
        Builtins.def("mb_strripos", 2, 4, (ctx, a) -> position(ctx, a, true, true));
        Builtins.def("mb_str_split", 1, 3, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 2, "mb_str_split");
            int n = a.length > 1 ? (int) PhpOps.toLong(a[1]) : 1;
            if (n < 1) {
                throw new PhpError(PhpError.VALUE_ERROR, "mb_str_split(): Argument #2 ($length) must be greater than 0", null);
            }
            String text = decode(ctx, PhpOps.toStr(a[0]), e);
            int count = text.codePointCount(0, text.length());
            PhpArray out = new PhpArray();
            for (int i = 0; i < count; i += n) {
                out.append(encode(ctx, cpSubstr(text, i, (long) n), e));
            }
            return out;
        });
        Builtins.def("mb_substr_count", 2, 3, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 2, "mb_substr_count");
            String hay = decode(ctx, PhpOps.toStr(a[0]), e);
            String needle = decode(ctx, PhpOps.toStr(a[1]), e);
            if (needle.isEmpty()) {
                throw new PhpError(PhpError.VALUE_ERROR, "mb_substr_count(): Argument #2 ($needle) must not be empty", null);
            }
            long count = 0;
            for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) {
                count++;
            }
            return count;
        });
        Builtins.def("mb_strwidth", 1, 2, (ctx, a) -> {
            String text = decode(ctx, PhpOps.toStr(a[0]), argumentEncoding(ctx, a, 1, "mb_strwidth"));
            long width = 0;
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i);
                width += wide(cp) ? 2 : 1;
                i += Character.charCount(cp);
            }
            return width;
        });
        Builtins.def("mb_str_pad", 2, 5, (ctx, a) -> {
            Encoding e = argumentEncoding(ctx, a, 4, "mb_str_pad");
            String text = decode(ctx, PhpOps.toStr(a[0]), e);
            String pad = a.length > 2 ? decode(ctx, PhpOps.toStr(a[2]), e) : " ";
            int length = (int) PhpOps.toLong(a[1]);
            int extra = length - text.codePointCount(0, text.length());
            if (pad.isEmpty()) {
                throw new PhpError(PhpError.VALUE_ERROR, "mb_str_pad(): Argument #3 ($pad_string) must be a non-empty string", null);
            }
            int type = a.length > 3 ? (int) PhpOps.toLong(a[3]) : 1;
            int left = extra <= 0 ? 0 : type == 0 ? extra : type == 2 ? extra / 2 : 0;
            return encode(ctx, cpFill(pad, left) + text + cpFill(pad, Math.max(0, extra - left)), e);
        });
    }

    private static String cpFill(String pad, int n) {
        if (n <= 0) {
            return "";
        }
        int count = pad.codePointCount(0, pad.length());
        return cpSubstr(pad.repeat((n + count - 1) / count), 0, (long) n);
    }

    private static boolean wide(int cp) {
        return cp >= 0x1100 && (cp <= 0x115F || cp == 0x2329 || cp == 0x232A || cp >= 0x2E80 && cp <= 0xA4CF
                || cp >= 0xAC00 && cp <= 0xD7A3 || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFE10 && cp <= 0xFE6F
                || cp >= 0xFF01 && cp <= 0xFF60 || cp >= 0xFFE0 && cp <= 0xFFE6 || cp >= 0x1F300 && cp <= 0x1FAFF
                || cp >= 0x20000 && cp <= 0x3FFFF);
    }

    static String cpSubstr(String text, long start, Long length) {
        int count = text.codePointCount(0, text.length());
        long from = start < 0 ? Math.max(0, count + start) : Math.min(count, start);
        long end = length == null ? count : length < 0 ? Math.max(from, count + length) : Math.min(count, from + length);
        return text.substring(text.offsetByCodePoints(0, (int) from), text.offsetByCodePoints(0, (int) end));
    }

    private static Object position(dev.phpruntime.PhpContext ctx, Object[] a, boolean last, boolean insensitive) {
        Encoding e = argumentEncoding(ctx, a, 3, "mb_strpos");
        String hay = decode(ctx, PhpOps.toStr(a[0]), e);
        String needle = decode(ctx, PhpOps.toStr(a[1]), e);
        long offset = a.length > 2 ? PhpOps.toLong(a[2]) : 0;
        int length = hay.codePointCount(0, hay.length());
        long from = offset < 0 ? length + offset : offset;
        if (from < 0 || from > length) {
            throw new PhpError(PhpError.VALUE_ERROR, "mb_strpos(): Argument #3 ($offset) must be contained in argument #1 ($haystack)", null);
        }
        int charOffset = hay.offsetByCodePoints(0, (int) from);
        // Search without changing UTF-16 indexes even when case folding expands a code point.
        int at = -1;
        if (insensitive) {
            var matcher = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle),
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(hay);
            while (matcher.find()) {
                if (last && offset < 0 ? matcher.start() <= charOffset : matcher.start() >= charOffset) {
                    at = matcher.start();
                    if (!last) {
                        break;
                    }
                }
            }
        } else if (!last) {
            at = hay.indexOf(needle, charOffset);
        } else {
            at = hay.lastIndexOf(needle, offset < 0 ? charOffset : hay.length());
            if (offset >= 0 && at < charOffset) {
                at = -1;
            }
        }
        return at < 0 ? false : (long) hay.codePointCount(0, at);
    }

    private static void iconv() {
        Builtins.def("utf8_encode", 1, 1, (ctx, a) -> PhpStrings.fromJava(PhpOps.toStr(a[0])));
        Builtins.def("utf8_decode", 1, 1, (ctx, a) -> PhpStrings.encode(PhpStrings.decodeUtf8(PhpOps.toStr(a[0]), "?"), StandardCharsets.ISO_8859_1, "?"));
        Builtins.def("iconv", 3, 3, (ctx, a) -> {
            Encoding from = require(PhpOps.toStr(a[0]), "iconv", 1);
            String target = PhpOps.toStr(a[1]);
            String[] parts = target.split("//");
            Encoding to = require(parts[0], "iconv", 2);
            boolean ignore = PhpStrings.upper(target).contains("//IGNORE");
            boolean translit = PhpStrings.upper(target).contains("//TRANSLIT");
            String data = PhpOps.toStr(a[2]);
            if (from.charset() != null && !PhpStrings.isEncoding(data, from.charset()) && !ignore) {
                return false;
            }
            String text = from.charset() == null ? decode(ctx, data, from) : PhpStrings.decode(data, from.charset(), ignore ? "" : "?");
            if (translit && to.charset() != null && !PhpStrings.canEncode(text, to.charset())) {
                text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                        .replace("ß", "ss").replace("Æ", "AE").replace("æ", "ae").replace("Ø", "O").replace("ø", "o");
            }
            if (to.charset() != null && !PhpStrings.canEncode(text, to.charset()) && !ignore && !translit) {
                return false;
            }
            return to.charset() == null ? encode(ctx, text, to) : PhpStrings.encode(text, to.charset(), ignore ? "" : "?");
        });
        Builtins.def("iconv_strlen", 1, 2, (ctx, a) -> {
            Encoding e = iconvEncoding(a, 1, "iconv_strlen");
            String s = PhpOps.toStr(a[0]);
            boolean ignore = iconvIgnore(a, 1);
            if (e.charset() != null && !PhpStrings.isEncoding(s, e.charset()) && !ignore) {
                return false;
            }
            String text = e.charset() == null ? decode(ctx, s, e) : PhpStrings.decode(s, e.charset(), ignore ? "" : "?");
            return (long) text.codePointCount(0, text.length());
        });
        Builtins.def("iconv_substr", 2, 4, (ctx, a) -> {
            Encoding e = iconvEncoding(a, 3, "iconv_substr");
            String s = PhpOps.toStr(a[0]);
            boolean ignore = iconvIgnore(a, 3);
            if (e.charset() != null && !PhpStrings.isEncoding(s, e.charset()) && !ignore) {
                return false;
            }
            String text = e.charset() == null ? decode(ctx, s, e) : PhpStrings.decode(s, e.charset(), ignore ? "" : "?");
            return encode(ctx, cpSubstr(text, PhpOps.toLong(a[1]),
                    a.length > 2 && a[2] != PhpNull.INSTANCE ? PhpOps.toLong(a[2]) : null), e);
        });
        Builtins.def("iconv_strpos", 2, 4, (ctx, a) -> {
            Encoding e = iconvEncoding(a, 3, "iconv_strpos");
            String hay = PhpOps.toStr(a[0]);
            String needle = PhpOps.toStr(a[1]);
            if (e.charset() != null && (!PhpStrings.isEncoding(hay, e.charset()) || !PhpStrings.isEncoding(needle, e.charset()))
                    && !iconvIgnore(a, 3)) {
                return false;
            }
            String text = e.charset() == null ? decode(ctx, hay, e) : PhpStrings.decode(hay, e.charset(), iconvIgnore(a, 3) ? "" : "?");
            String sought = decode(ctx, needle, e);
            long offset = a.length > 2 ? PhpOps.toLong(a[2]) : 0;
            int count = text.codePointCount(0, text.length());
            if (offset < 0 || offset > count) {
                throw new PhpError(PhpError.VALUE_ERROR, "iconv_strpos(): Argument #3 ($offset) must be contained in argument #1 ($haystack)", null);
            }
            int at = text.indexOf(sought, text.offsetByCodePoints(0, (int) offset));
            return at < 0 ? false : (long) text.codePointCount(0, at);
        });
    }

    private static Encoding iconvEncoding(Object[] a, int i, String fn) {
        String name = a.length > i && a[i] != PhpNull.INSTANCE ? PhpOps.toStr(a[i]) : "UTF-8";
        return require(name.split("//")[0], fn, i + 1);
    }

    private static boolean iconvIgnore(Object[] a, int i) {
        return a.length > i && PhpStrings.upper(PhpOps.toStr(a[i])).contains("//IGNORE");
    }
}
