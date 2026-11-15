package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** String functions, sprintf and number_format. */
final class StringFunctions {

    private static final String DEFAULT_TRIM = " \t\n\r\0\u000B";

    private StringFunctions() {
    }

    private static String s(Object[] a, int i) {
        return PhpOps.toStr(a[i]);
    }

    private static long l(Object[] a, int i) {
        return PhpOps.toLong(a[i]);
    }

    static void register() {
        basics();
        searching();
        splitting();
        formatting();
        encoding();
        more();
    }

    private static void basics() {
        Builtins.def("strlen", 1, 1, (ctx, a) -> (long) s(a, 0).getBytes(StandardCharsets.UTF_8).length);
        Builtins.def("mb_strlen", 1, 2, (ctx, a) -> (long) s(a, 0).codePointCount(0, s(a, 0).length()));
        Builtins.def("strtoupper", 1, 1, (ctx, a) -> s(a, 0).toUpperCase());
        Builtins.def("strtolower", 1, 1, (ctx, a) -> s(a, 0).toLowerCase());
        Builtins.def("mb_strtoupper", 1, 2, (ctx, a) -> s(a, 0).toUpperCase());
        Builtins.def("mb_strtolower", 1, 2, (ctx, a) -> s(a, 0).toLowerCase());
        Builtins.def("ucfirst", 1, 1, (ctx, a) -> {
            String x = s(a, 0);
            return x.isEmpty() ? x : Character.toUpperCase(x.charAt(0)) + x.substring(1);
        });
        Builtins.def("lcfirst", 1, 1, (ctx, a) -> {
            String x = s(a, 0);
            return x.isEmpty() ? x : Character.toLowerCase(x.charAt(0)) + x.substring(1);
        });
        Builtins.def("ucwords", 1, 2, (ctx, a) -> {
            String delims = a.length > 1 ? s(a, 1) : " \t\r\n\f\u000B";
            StringBuilder sb = new StringBuilder(s(a, 0));
            boolean up = true;
            for (int i = 0; i < sb.length(); i++) {
                if (up) {
                    sb.setCharAt(i, Character.toUpperCase(sb.charAt(i)));
                }
                up = delims.indexOf(sb.charAt(i)) >= 0;
            }
            return sb.toString();
        });
        Builtins.def("str_repeat", 2, 2, (ctx, a) -> {
            long n = l(a, 1);
            if (n < 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "str_repeat(): Argument #2 ($times) must be greater than or equal to 0", null);
            }
            return s(a, 0).repeat((int) n);
        });
        Builtins.def("strrev", 1, 1, (ctx, a) -> new StringBuilder(s(a, 0)).reverse().toString());
        Builtins.def("substr", 2, 3, (ctx, a) -> substr(s(a, 0), l(a, 1), a.length > 2 && a[2] != PhpNull.INSTANCE ? l(a, 2) : null));
        Builtins.def("mb_substr", 2, 4, (ctx, a) -> substr(s(a, 0), l(a, 1), a.length > 2 && a[2] != PhpNull.INSTANCE ? l(a, 2) : null));
        Builtins.def("trim", 1, 2, (ctx, a) -> trim(s(a, 0), a.length > 1 ? s(a, 1) : DEFAULT_TRIM, true, true));
        Builtins.def("ltrim", 1, 2, (ctx, a) -> trim(s(a, 0), a.length > 1 ? s(a, 1) : DEFAULT_TRIM, true, false));
        Builtins.def("rtrim", 1, 2, (ctx, a) -> trim(s(a, 0), a.length > 1 ? s(a, 1) : DEFAULT_TRIM, false, true));
        Builtins.def("chop", 1, 2, (ctx, a) -> trim(s(a, 0), a.length > 1 ? s(a, 1) : DEFAULT_TRIM, false, true));
        Builtins.def("str_pad", 2, 4, (ctx, a) -> strPad(s(a, 0), (int) l(a, 1), a.length > 2 ? s(a, 2) : " ", a.length > 3 ? (int) l(a, 3) : 1));
        Builtins.def("strcmp", 2, 2, (ctx, a) -> (long) Integer.signum(s(a, 0).compareTo(s(a, 1))));
        Builtins.def("strcasecmp", 2, 2, (ctx, a) -> (long) Integer.signum(s(a, 0).compareToIgnoreCase(s(a, 1))));
        Builtins.def("strncmp", 3, 3, (ctx, a) -> {
            int n = (int) l(a, 2);
            String x = s(a, 0);
            String y = s(a, 1);
            return (long) Integer.signum(x.substring(0, Math.min(n, x.length())).compareTo(y.substring(0, Math.min(n, y.length()))));
        });
        Builtins.def("ord", 1, 1, (ctx, a) -> {
            byte[] b = s(a, 0).getBytes(StandardCharsets.UTF_8);
            return b.length == 0 ? 0L : (long) (b[0] & 0xff);
        });
        Builtins.def("chr", 1, 1, (ctx, a) -> String.valueOf((char) Math.floorMod(l(a, 0), 256)));
        Builtins.def("nl2br", 1, 2, (ctx, a) -> s(a, 0).replace("\r\n", "<br />\r\n").replaceAll("(?<!\r)\n", "<br />\n"));
        Builtins.def("str_word_count", 1, 1, (ctx, a) -> {
            String x = s(a, 0).strip();
            return x.isEmpty() ? 0L : (long) x.split("[^A-Za-z'-]+").length;
        });
        Builtins.def("wordwrap", 1, 4, (ctx, a) -> wordwrap(s(a, 0), a.length > 1 ? (int) l(a, 1) : 75,
                a.length > 2 ? s(a, 2) : "\n", a.length > 3 && PhpOps.toBool(a[3])));
    }

    private static void searching() {
        Builtins.def("strpos", 2, 3, (ctx, a) -> indexResult(s(a, 0).indexOf(s(a, 1), offset(s(a, 0), a))));
        Builtins.def("stripos", 2, 3, (ctx, a) -> indexResult(s(a, 0).toLowerCase().indexOf(s(a, 1).toLowerCase(), offset(s(a, 0), a))));
        Builtins.def("strrpos", 2, 3, (ctx, a) -> indexResult(s(a, 0).lastIndexOf(s(a, 1))));
        Builtins.def("str_contains", 2, 2, (ctx, a) -> s(a, 0).contains(s(a, 1)));
        Builtins.def("str_starts_with", 2, 2, (ctx, a) -> s(a, 0).startsWith(s(a, 1)));
        Builtins.def("str_ends_with", 2, 2, (ctx, a) -> s(a, 0).endsWith(s(a, 1)));
        Builtins.def("strstr", 2, 3, (ctx, a) -> {
            int i = s(a, 0).indexOf(s(a, 1));
            if (i < 0) {
                return false;
            }
            return a.length > 2 && PhpOps.toBool(a[2]) ? s(a, 0).substring(0, i) : s(a, 0).substring(i);
        });
        Builtins.def("substr_count", 2, 2, (ctx, a) -> {
            String h = s(a, 0);
            String n = s(a, 1);
            long count = 0;
            for (int i = h.indexOf(n); i >= 0 && !n.isEmpty(); i = h.indexOf(n, i + n.length())) {
                count++;
            }
            return count;
        });
        Builtins.def("str_replace", 3, 3, (ctx, a) -> replace(a[0], a[1], a[2], false));
        Builtins.def("str_ireplace", 3, 3, (ctx, a) -> replace(a[0], a[1], a[2], true));
    }

    private static void splitting() {
        Builtins.def("explode", 2, 3, (ctx, a) -> {
            String sep = s(a, 0);
            if (sep.isEmpty()) {
                throw new PhpError(PhpError.VALUE_ERROR, "explode(): Argument #1 ($separator) must not be empty", null);
            }
            String str = s(a, 1);
            long limit = a.length > 2 ? l(a, 2) : Long.MAX_VALUE;
            PhpArray out = new PhpArray();
            int start = 0;
            java.util.ArrayList<String> parts = new java.util.ArrayList<>();
            for (int i = str.indexOf(sep); i >= 0; i = str.indexOf(sep, start)) {
                if (limit > 0 && parts.size() == limit - 1) {
                    break;
                }
                parts.add(str.substring(start, i));
                start = i + sep.length();
            }
            parts.add(str.substring(start));
            if (limit < 0) {
                parts.subList(Math.max(0, parts.size() + (int) limit), parts.size()).clear();
            }
            parts.forEach(out::append);
            return out;
        });
        Builtins.def("implode", 1, 2, StringFunctions::implode);
        Builtins.def("join", 1, 2, StringFunctions::implode);
        Builtins.def("str_split", 1, 2, (ctx, a) -> {
            String str = s(a, 0);
            int n = a.length > 1 ? (int) l(a, 1) : 1;
            PhpArray out = new PhpArray();
            for (int i = 0; i < str.length(); i += n) {
                out.append(str.substring(i, Math.min(str.length(), i + n)));
            }
            if (str.isEmpty()) {
                out.append("");
            }
            return out;
        });
    }

    private static void formatting() {
        Builtins.def("sprintf", 1, -1, (ctx, a) -> sprintf(a));
        Builtins.def("vsprintf", 2, 2, (ctx, a) -> {
            List<Object> vals = ((PhpArray) a[1]).values();
            Object[] args = new Object[vals.size() + 1];
            args[0] = a[0];
            for (int i = 0; i < vals.size(); i++) {
                args[i + 1] = vals.get(i);
            }
            return sprintf(args);
        });
        Builtins.def("number_format", 1, 4, (ctx, a) -> numberFormat(PhpOps.toDouble(a[0]), a.length > 1 ? (int) l(a, 1) : 0,
                a.length > 2 ? s(a, 2) : ".", a.length > 3 ? s(a, 3) : ","));
        Builtins.def("dechex", 1, 1, (ctx, a) -> Long.toHexString(l(a, 0)));
        Builtins.def("hexdec", 1, 1, (ctx, a) -> Long.parseLong(s(a, 0).replaceAll("[^0-9a-fA-F]", "").isEmpty() ? "0" : s(a, 0).replaceAll("[^0-9a-fA-F]", ""), 16));
    }

    private static void encoding() {
        Builtins.def("htmlspecialchars", 1, 4, (ctx, a) -> htmlspecialchars(s(a, 0)));
        Builtins.def("htmlentities", 1, 4, (ctx, a) -> htmlspecialchars(s(a, 0)));
        Builtins.def("strip_tags", 1, 2, (ctx, a) -> s(a, 0).replaceAll("<[^>]*>", ""));
        Builtins.def("addslashes", 1, 1, (ctx, a) -> s(a, 0).replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\0", "\\0"));
        Builtins.def("base64_encode", 1, 1, (ctx, a) -> java.util.Base64.getEncoder().encodeToString(s(a, 0).getBytes(StandardCharsets.UTF_8)));
        Builtins.def("base64_decode", 1, 2, (ctx, a) -> new String(java.util.Base64.getDecoder().decode(s(a, 0)), StandardCharsets.UTF_8));
        Builtins.def("bin2hex", 1, 1, (ctx, a) -> HexFormat.of().formatHex(s(a, 0).getBytes(StandardCharsets.UTF_8)));
        Builtins.def("md5", 1, 1, (ctx, a) -> digest("MD5", s(a, 0)));
        Builtins.def("sha1", 1, 1, (ctx, a) -> digest("SHA-1", s(a, 0)));
        Builtins.def("crc32", 1, 1, (ctx, a) -> {
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            c.update(s(a, 0).getBytes(StandardCharsets.UTF_8));
            return c.getValue();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Object indexResult(int i) {
        return i < 0 ? (Object) false : (Object) (long) i;
    }

    private static int offset(String hay, Object[] a) {
        if (a.length < 3) {
            return 0;
        }
        long off = l(a, 2);
        return (int) (off < 0 ? Math.max(0, hay.length() + off) : off);
    }

    static String substr(String str, long start, Long length) {
        int n = str.length();
        if (start < 0) {
            start = Math.max(0, n + start);
        }
        if (start > n) {
            return "";
        }
        long end = n;
        if (length != null) {
            end = length < 0 ? n + length : Math.min(n, start + length);
        }
        if (end <= start) {
            return "";
        }
        return str.substring((int) start, (int) end);
    }

    private static String trim(String str, String chars, boolean left, boolean right) {
        String set = expandRanges(chars);
        int b = 0;
        int e = str.length();
        while (left && b < e && set.indexOf(str.charAt(b)) >= 0) {
            b++;
        }
        while (right && e > b && set.indexOf(str.charAt(e - 1)) >= 0) {
            e--;
        }
        return str.substring(b, e);
    }

    /** Expands "a..z" character ranges in trim's character list. */
    private static String expandRanges(String chars) {
        if (!chars.contains("..")) {
            return chars;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars.length(); i++) {
            if (i + 3 < chars.length() && chars.startsWith("..", i + 1)) {
                for (char c = chars.charAt(i); c <= chars.charAt(i + 3); c++) {
                    sb.append(c);
                }
                i += 3;
            } else {
                sb.append(chars.charAt(i));
            }
        }
        return sb.toString();
    }

    private static String strPad(String str, int length, String pad, int type) {
        int total = length - str.length();
        if (total <= 0 || pad.isEmpty()) {
            return str;
        }
        int left = type == 0 ? total : type == 2 ? total / 2 : 0;
        int right = total - left;
        return fill(pad, left) + str + fill(pad, right);
    }

    private static String fill(String pad, int n) {
        StringBuilder sb = new StringBuilder(n);
        while (sb.length() < n) {
            sb.append(pad);
        }
        sb.setLength(n);
        return sb.toString();
    }

    private static String wordwrap(String str, int width, String brk, boolean cut) {
        String[] words = str.split(" ", -1);
        StringBuilder out = new StringBuilder();
        int lineLen = 0;
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            while (cut && w.length() > width) {
                if (lineLen > 0) {
                    out.append(brk);
                    lineLen = 0;
                }
                out.append(w, 0, width).append(brk);
                w = w.substring(width);
            }
            if (i > 0) {
                if (lineLen + 1 + w.length() > width) {
                    out.append(brk);
                    lineLen = 0;
                } else {
                    out.append(' ');
                    lineLen++;
                }
            }
            out.append(w);
            lineLen += w.length();
        }
        return out.toString();
    }

    private static Object replace(Object search, Object replacement, Object subject, boolean ignoreCase) {
        if (subject instanceof PhpArray arr) {
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : arr.entries()) {
                out.put(e.getKey(), replace(search, replacement, e.getValue(), ignoreCase));
            }
            return out;
        }
        String result = PhpOps.toStr(subject);
        if (search instanceof PhpArray searches) {
            List<Object> from = searches.values();
            List<Object> to = replacement instanceof PhpArray r ? r.values() : null;
            for (int i = 0; i < from.size(); i++) {
                String rep = to == null ? PhpOps.toStr(replacement) : (i < to.size() ? PhpOps.toStr(to.get(i)) : "");
                result = replaceOne(result, PhpOps.toStr(from.get(i)), rep, ignoreCase);
            }
            return result;
        }
        return replaceOne(result, PhpOps.toStr(search), PhpOps.toStr(replacement), ignoreCase);
    }

    private static String replaceOne(String str, String from, String to, boolean ignoreCase) {
        if (from.isEmpty()) {
            return str;
        }
        if (!ignoreCase) {
            return str.replace(from, to);
        }
        return str.replaceAll("(?i)" + java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to));
    }

    private static Object implode(dev.phpruntime.PhpContext ctx, Object[] a) {
        String glue;
        PhpArray arr;
        if (a.length == 1) {
            glue = "";
            arr = (PhpArray) a[0];
        } else if (a[0] instanceof PhpArray x) {
            arr = x;
            glue = s(a, 1);
        } else if (a[1] instanceof PhpArray y) {
            arr = y;
            glue = s(a, 0);
        } else {
            throw new PhpError(PhpError.TYPE_ERROR, "implode(): Argument #2 ($array) must be of type ?array, " + PhpOps.typeName(a[1]) + " given", null);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object v : arr.values()) {
            if (!first) {
                sb.append(glue);
            }
            sb.append(PhpOps.toStr(v));
            first = false;
        }
        return sb.toString();
    }

    static String htmlspecialchars(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#039;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String digest(String algorithm, String str) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(str.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new PhpError(algorithm + " unavailable");
        }
    }

    /** PHP rounding: half away from zero. */
    static String numberFormat(double num, int decimals, String decPoint, String thousands) {
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(num))).setScale(Math.max(0, decimals), RoundingMode.HALF_UP);
        String plain = bd.toPlainString();
        String intPart = plain;
        String frac = "";
        int dot = plain.indexOf('.');
        if (dot >= 0) {
            intPart = plain.substring(0, dot);
            frac = plain.substring(dot + 1);
        }
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) {
                grouped.append(thousands);
            }
            grouped.append(intPart.charAt(i));
        }
        boolean negative = num < 0 && bd.signum() != 0;
        return (negative ? "-" : "") + grouped + (decimals > 0 ? decPoint + frac : "");
    }

    // ------------------------------------------------------------------ sprintf

    static String sprintf(Object[] a) {
        String fmt = PhpOps.toStr(a[0]);
        StringBuilder out = new StringBuilder();
        int argIndex = 1;
        int i = 0;
        while (i < fmt.length()) {
            char c = fmt.charAt(i++);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (i < fmt.length() && fmt.charAt(i) == '%') {
                out.append('%');
                i++;
                continue;
            }
            // %[argnum$][flags][width][.precision]specifier
            int argnum = -1;
            int j = i;
            while (j < fmt.length() && Character.isDigit(fmt.charAt(j))) {
                j++;
            }
            if (j < fmt.length() && j > i && fmt.charAt(j) == '$') {
                argnum = Integer.parseInt(fmt.substring(i, j));
                i = j + 1;
            }
            boolean left = false;
            boolean plus = false;
            char pad = ' ';
            while (i < fmt.length()) {
                char f = fmt.charAt(i);
                if (f == '-') {
                    left = true;
                } else if (f == '+') {
                    plus = true;
                } else if (f == '0') {
                    pad = '0';
                } else if (f == ' ') {
                    pad = ' ';
                } else if (f == '\'' && i + 1 < fmt.length()) {
                    pad = fmt.charAt(++i);
                } else {
                    break;
                }
                i++;
            }
            int width = 0;
            while (i < fmt.length() && Character.isDigit(fmt.charAt(i))) {
                width = width * 10 + (fmt.charAt(i++) - '0');
            }
            int precision = -1;
            if (i < fmt.length() && fmt.charAt(i) == '.') {
                i++;
                precision = 0;
                while (i < fmt.length() && Character.isDigit(fmt.charAt(i))) {
                    precision = precision * 10 + (fmt.charAt(i++) - '0');
                }
            }
            if (i >= fmt.length()) {
                int needed = (argnum > 0 ? argnum : argIndex) + 1;
                if (needed > a.length) {
                    throw new PhpError(PhpError.ARGUMENT_COUNT, needed + " arguments are required, " + a.length + " given", null);
                }
                throw new PhpError(PhpError.VALUE_ERROR, "Missing format specifier at end of string", null);
            }
            char spec = fmt.charAt(i++);
            int idx = argnum > 0 ? argnum : argIndex++;
            if (idx >= a.length) {
                throw new PhpError(PhpError.ARGUMENT_COUNT, (idx + 1) + " arguments are required, " + a.length + " given", null);
            }
            Object arg = a[idx];
            String body = switch (spec) {
                case 'd', 'i' -> {
                    long v = PhpOps.toLong(arg);
                    yield (plus && v >= 0 ? "+" : "") + v;
                }
                case 'u' -> Long.toUnsignedString(PhpOps.toLong(arg));
                case 's' -> {
                    String v = PhpOps.toStr(arg);
                    yield precision >= 0 && precision < v.length() ? v.substring(0, precision) : v;
                }
                case 'f', 'F' -> {
                    double v = PhpOps.toDouble(arg);
                    String r = new BigDecimal(v).setScale(precision < 0 ? 6 : precision, RoundingMode.HALF_UP).toPlainString();
                    yield (plus && v >= 0 ? "+" : "") + r;
                }
                case 'e', 'E' -> {
                    String r = String.format("%." + (precision < 0 ? 6 : precision) + "e", PhpOps.toDouble(arg));
                    r = r.replaceAll("e([+-])0*(\\d)", "e$1$2");
                    yield spec == 'E' ? r.toUpperCase() : r;
                }
                case 'x' -> Long.toHexString(PhpOps.toLong(arg));
                case 'X' -> Long.toHexString(PhpOps.toLong(arg)).toUpperCase();
                case 'o' -> Long.toOctalString(PhpOps.toLong(arg));
                case 'b' -> Long.toBinaryString(PhpOps.toLong(arg));
                case 'c' -> String.valueOf((char) PhpOps.toLong(arg));
                default -> throw new PhpError(PhpError.VALUE_ERROR, "Unknown format specifier " + '"' + spec + '"', null);
            };
            if (body.length() < width) {
                String padding = String.valueOf(pad).repeat(width - body.length());
                if (left) {
                    body = body + (pad == '0' ? " ".repeat(width - body.length()) : padding);
                } else if (pad == '0' && (body.startsWith("-") || body.startsWith("+")) && "dieEfF".indexOf(spec) >= 0) {
                    body = body.charAt(0) + padding + body.substring(1);
                } else {
                    body = padding + body;
                }
            }
            out.append(body);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ more

    private static void more() {
        Builtins.def("strspn", 2, 4, (ctx, a) -> (long) span(segment(a), s(a, 1), true));
        Builtins.def("strcspn", 2, 4, (ctx, a) -> (long) span(segment(a), s(a, 1), false));
        Builtins.def("substr_compare", 3, 5, (ctx, a) -> substrCompare(a));
        Builtins.def("strtr", 2, 3, (ctx, a) -> {
            if (a.length == 3) {
                return strtrChars(s(a, 0), s(a, 1), s(a, 2));
            }
            if (!(a[1] instanceof PhpArray map)) {
                throw new PhpError(PhpError.TYPE_ERROR, "strtr(): Argument #2 ($from) must be of type array, " + PhpOps.typeName(a[1]) + " given", null);
            }
            return strtrPairs(ctx, s(a, 0), map);
        });
        Builtins.def("version_compare", 2, 3, (ctx, a) -> {
            int c = versionCompare(s(a, 0), s(a, 1));
            if (a.length < 3 || a[2] == PhpNull.INSTANCE) {
                return (long) c;
            }
            return switch (s(a, 2)) {
                case "<", "lt" -> c < 0;
                case "<=", "le" -> c <= 0;
                case ">", "gt" -> c > 0;
                case ">=", "ge" -> c >= 0;
                case "==", "eq" -> c == 0;
                case "!=", "<>", "ne" -> c != 0;
                default -> throw new PhpError(PhpError.VALUE_ERROR, "version_compare(): Argument #3 ($operator) must be a valid comparison operator", null);
            };
        });
        Builtins.def("stripslashes", 1, 1, (ctx, a) -> stripslashes(s(a, 0)));
        Builtins.def("addcslashes", 2, 2, (ctx, a) -> addcslashes(s(a, 0), s(a, 1)));
        Builtins.def("stripcslashes", 1, 1, (ctx, a) -> stripcslashes(s(a, 0)));
        Builtins.def("strnatcmp", 2, 2, (ctx, a) -> (long) natCompare(s(a, 0), s(a, 1), false));
        Builtins.def("strnatcasecmp", 2, 2, (ctx, a) -> (long) natCompare(s(a, 0), s(a, 1), true));
        Builtins.def("strtok", 1, 2, (ctx, a) -> {
            ContextState st = ContextState.of(ctx);
            String token;
            if (a.length == 2) {
                st.strtokSubject = s(a, 0);
                st.strtokPos = 0;
                token = s(a, 1);
            } else {
                token = s(a, 0);
            }
            return strtok(st, token);
        });
        Builtins.def("substr_replace", 3, 4, (ctx, a) -> substrReplace(a));
        Builtins.def("stristr", 2, 3, (ctx, a) -> {
            String hay = s(a, 0);
            int i = hay.toLowerCase().indexOf(s(a, 1).toLowerCase());
            if (i < 0) {
                return false;
            }
            return a.length > 2 && PhpOps.toBool(a[2]) ? hay.substring(0, i) : hay.substring(i);
        });
        Builtins.def("strrchr", 2, 3, (ctx, a) -> {
            String hay = s(a, 0);
            String needle = s(a, 1);
            int i = needle.isEmpty() ? -1 : hay.lastIndexOf(needle.charAt(0));
            if (i < 0) {
                return false;
            }
            return a.length > 2 && PhpOps.toBool(a[2]) ? hay.substring(0, i) : hay.substring(i);
        });
        Builtins.def("strpbrk", 2, 2, (ctx, a) -> {
            String str = s(a, 0);
            String chars = s(a, 1);
            if (chars.isEmpty()) {
                throw new PhpError(PhpError.VALUE_ERROR, "strpbrk(): Argument #2 ($characters) must be a non-empty string", null);
            }
            for (int i = 0; i < str.length(); i++) {
                if (chars.indexOf(str.charAt(i)) >= 0) {
                    return str.substring(i);
                }
            }
            return false;
        });
        Builtins.def("html_entity_decode", 1, 3, (ctx, a) ->
                HtmlEntities.decodeAll(s(a, 0), a.length > 1 ? l(a, 1) : HtmlEntities.DEFAULT_FLAGS));
        Builtins.def("htmlspecialchars_decode", 1, 2, (ctx, a) ->
                HtmlEntities.decodeSpecial(s(a, 0), a.length > 1 ? l(a, 1) : HtmlEntities.DEFAULT_FLAGS));
        Builtins.def("chunk_split", 1, 3, (ctx, a) -> {
            String str = s(a, 0);
            long len = a.length > 1 ? l(a, 1) : 76;
            String end = a.length > 2 ? s(a, 2) : "\r\n";
            if (len < 1) {
                throw new PhpError(PhpError.VALUE_ERROR, "chunk_split(): Argument #2 ($length) must be greater than 0", null);
            }
            if (len >= str.length()) {
                return str + end;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i += (int) len) {
                sb.append(str, i, (int) Math.min(str.length(), i + len)).append(end);
            }
            return sb.toString();
        });
        Builtins.def("uniqid", 0, 2, (ctx, a) -> {
            long micros = System.currentTimeMillis() * 1000 + (System.nanoTime() / 1000) % 1000;
            String id = (a.length > 0 ? s(a, 0) : "") + String.format("%08x%05x", micros / 1_000_000, micros % 1_000_000);
            if (a.length > 1 && PhpOps.toBool(a[1])) {
                id += String.format(java.util.Locale.ROOT, "%.8f", Math.random() * 10);
            }
            return id;
        });
    }

    /** The part of a[0] selected by strspn/strcspn's optional offset and length. */
    private static String segment(Object[] a) {
        String str = s(a, 0);
        if (a.length < 3) {
            return str;
        }
        Long len = a.length > 3 && a[3] != PhpNull.INSTANCE ? l(a, 3) : null;
        return substr(str, l(a, 2), len);
    }

    private static int span(String str, String mask, boolean accept) {
        int i = 0;
        while (i < str.length() && (mask.indexOf(str.charAt(i)) >= 0) == accept) {
            i++;
        }
        return i;
    }

    private static Object substrCompare(Object[] a) {
        String hay = s(a, 0);
        String needle = s(a, 1);
        long offset = l(a, 2);
        boolean lenDefault = a.length < 4 || a[3] == PhpNull.INSTANCE;
        long len = lenDefault ? 0 : l(a, 3);
        boolean ci = a.length > 4 && PhpOps.toBool(a[4]);
        if (!lenDefault && len <= 0) {
            if (len == 0) {
                return 0L;
            }
            throw new PhpError(PhpError.VALUE_ERROR, "substr_compare(): Argument #4 ($length) must be greater than or equal to 0", null);
        }
        if (offset < 0) {
            offset = Math.max(0, hay.length() + offset);
        }
        if (offset > hay.length()) {
            throw new PhpError(PhpError.VALUE_ERROR, "substr_compare(): Argument #3 ($offset) must be contained in argument #1 ($haystack)", null);
        }
        String s1 = hay.substring((int) offset);
        long cmpLen = lenDefault ? Math.max(needle.length(), s1.length()) : len;
        int n = (int) Math.min(cmpLen, Math.min(s1.length(), needle.length()));
        for (int i = 0; i < n; i++) {
            char x = s1.charAt(i);
            char y = needle.charAt(i);
            if (ci) {
                x = Character.toLowerCase(x);
                y = Character.toLowerCase(y);
            }
            if (x != y) {
                return (long) (x - y);
            }
        }
        return (long) Long.compare(Math.min(cmpLen, s1.length()), Math.min(cmpLen, needle.length()));
    }

    private static String strtrChars(String str, String from, String to) {
        int n = Math.min(from.length(), to.length());
        if (n == 0) {
            return str;
        }
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int k = from.lastIndexOf(c, n - 1);
            sb.append(k >= 0 ? to.charAt(k) : c);
        }
        return sb.toString();
    }

    /** strtr with an array: longest key first at each position; replaced text is not rescanned. */
    private static String strtrPairs(dev.phpruntime.PhpContext ctx, String str, PhpArray map) {
        java.util.HashMap<String, String> pairs = new java.util.HashMap<>();
        int maxLen = 0;
        int minLen = Integer.MAX_VALUE;
        for (Map.Entry<Object, Object> e : map.entries()) {
            String k = PhpOps.toStr(e.getKey());
            if (k.isEmpty()) {
                ctx.warning("strtr(): Ignoring replacement of empty string", 0);
                continue;
            }
            pairs.put(k, PhpOps.toStr(e.getValue()));
            maxLen = Math.max(maxLen, k.length());
            minLen = Math.min(minLen, k.length());
        }
        if (pairs.isEmpty()) {
            return str;
        }
        StringBuilder sb = new StringBuilder(str.length());
        int i = 0;
        outer:
        while (i < str.length()) {
            for (int len = Math.min(maxLen, str.length() - i); len >= minLen; len--) {
                String rep = pairs.get(str.substring(i, i + len));
                if (rep != null) {
                    sb.append(rep);
                    i += len;
                    continue outer;
                }
            }
            sb.append(str.charAt(i++));
        }
        return sb.toString();
    }

    // version_compare, ported from ext/standard/versioning.c

    private static boolean isDig(char c) {
        return c >= '0' && c <= '9';
    }

    private static String canonicalizeVersion(String v) {
        StringBuilder q = new StringBuilder();
        char lp = v.charAt(0);
        q.append(lp);
        for (int i = 1; i < v.length(); i++) {
            char p = v.charAt(i);
            char last = q.charAt(q.length() - 1);
            if (p == '-' || p == '_' || p == '+') {
                if (last != '.') {
                    q.append('.');
                }
            } else if ((!isDig(lp) && lp != '.' && isDig(p)) || (isDig(lp) && !isDig(p) && p != '.')) {
                if (last != '.') {
                    q.append('.');
                }
                q.append(p);
            } else if (p > 127 || !Character.isLetterOrDigit(p)) {
                if (last != '.') {
                    q.append('.');
                }
            } else {
                q.append(p);
            }
            lp = p;
        }
        return q.toString();
    }

    private static int specialForm(String s) {
        String[] names = {"dev", "alpha", "a", "beta", "b", "RC", "rc", "#", "pl", "p"};
        int[] order = {0, 1, 1, 2, 2, 3, 3, 4, 5, 5};
        for (int i = 0; i < names.length; i++) {
            if (s.startsWith(names[i])) {
                return order[i];
            }
        }
        return -1;
    }

    private static int compareSpecial(String a, String b) {
        return Integer.signum(specialForm(a) - specialForm(b));
    }

    static int versionCompare(String v1, String v2) {
        if (v1.isEmpty() || v2.isEmpty()) {
            return v1.isEmpty() && v2.isEmpty() ? 0 : (v1.isEmpty() ? -1 : 1);
        }
        String[] p1 = (v1.charAt(0) == '#' ? v1 : canonicalizeVersion(v1)).split("\\.", -1);
        String[] p2 = (v2.charAt(0) == '#' ? v2 : canonicalizeVersion(v2)).split("\\.", -1);
        int i = 0;
        while (i < p1.length && i < p2.length) {
            String a = p1[i];
            String b = p2[i];
            if (a.isEmpty() || b.isEmpty()) {
                break;
            }
            boolean da = isDig(a.charAt(0));
            boolean db = isDig(b.charAt(0));
            int compare;
            if (da && db) {
                compare = Long.signum(leadingLong(a) - leadingLong(b));
            } else if (!da && !db) {
                compare = compareSpecial(a, b);
            } else if (da) {
                compare = compareSpecial("#N#", b);
            } else {
                compare = compareSpecial(a, "#N#");
            }
            if (compare != 0) {
                return compare;
            }
            i++;
        }
        if (i < p1.length && i >= p2.length) {
            String rest = p1[i];
            return !rest.isEmpty() && isDig(rest.charAt(0)) ? 1
                    : versionCompare(String.join(".", java.util.Arrays.copyOfRange(p1, i, p1.length)), "#N#");
        }
        if (i < p2.length && i >= p1.length) {
            String rest = p2[i];
            return !rest.isEmpty() && isDig(rest.charAt(0)) ? -1
                    : versionCompare("#N#", String.join(".", java.util.Arrays.copyOfRange(p2, i, p2.length)));
        }
        return 0;
    }

    private static long leadingLong(String s) {
        long v = 0;
        for (int i = 0; i < s.length() && isDig(s.charAt(i)) && v < Long.MAX_VALUE / 10; i++) {
            v = v * 10 + (s.charAt(i) - '0');
        }
        return v;
    }

    private static String stripslashes(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c != '\\') {
                sb.append(c);
            } else if (i + 1 < str.length()) {
                char n = str.charAt(++i);
                sb.append(n == '0' ? '\0' : n);
            }
        }
        return sb.toString();
    }

    /** addcslashes works on bytes, as PHP does, so multi-byte characters are escaped byte by byte. */
    private static String addcslashes(String str, String charlist) {
        boolean[] mask = new boolean[256];
        byte[] list = charlist.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < list.length; i++) {
            int c = list[i] & 0xFF;
            if (i + 3 < list.length && list[i + 1] == '.' && list[i + 2] == '.' && (list[i + 3] & 0xFF) >= c) {
                for (int k = c; k <= (list[i + 3] & 0xFF); k++) {
                    mask[k] = true;
                }
                i += 3;
            } else {
                mask[c] = true;
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte b : str.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (!mask[c]) {
                out.write(c);
                continue;
            }
            out.write('\\');
            if (c < 32 || c > 126) {
                String esc = switch (c) {
                    case '\n' -> "n";
                    case '\t' -> "t";
                    case '\r' -> "r";
                    case 7 -> "a";
                    case 11 -> "v";
                    case '\b' -> "b";
                    case '\f' -> "f";
                    default -> String.format("%03o", c);
                };
                out.writeBytes(esc.getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String stripcslashes(String str) {
        byte[] in = str.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < in.length; i++) {
            if (in[i] != '\\') {
                out.write(in[i]);
                continue;
            }
            if (i + 1 >= in.length) {
                continue;
            }
            char c = (char) in[++i];
            switch (c) {
                case 'n' -> out.write('\n');
                case 't' -> out.write('\t');
                case 'r' -> out.write('\r');
                case 'a' -> out.write(7);
                case 'v' -> out.write(11);
                case 'b' -> out.write('\b');
                case 'f' -> out.write('\f');
                case 'x' -> {
                    int v = 0;
                    int k = 0;
                    while (k < 2 && i + 1 < in.length && Character.digit(in[i + 1], 16) >= 0) {
                        v = v * 16 + Character.digit(in[++i], 16);
                        k++;
                    }
                    out.write(k == 0 ? 'x' : v);
                }
                default -> {
                    if (c >= '0' && c <= '7') {
                        int v = c - '0';
                        int k = 1;
                        while (k < 3 && i + 1 < in.length && in[i + 1] >= '0' && in[i + 1] <= '7') {
                            v = v * 8 + (in[++i] - '0');
                            k++;
                        }
                        out.write(v & 0xFF);
                    } else {
                        out.write(in[i]);
                    }
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // strnatcmp, ported from ext/standard/strnatcmp.c

    private static char at(String s, int i) {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    static int natCompare(String a, String b, boolean ci) {
        int alen = a.length();
        int blen = b.length();
        if (alen == 0 || blen == 0) {
            return alen == blen ? 0 : (alen > blen ? 1 : -1);
        }
        int ap = 0;
        int bp = 0;
        boolean leading = true;
        while (true) {
            char ca = at(a, ap);
            char cb = at(b, bp);
            while (leading && ca == '0' && ap + 1 < alen && isDig(a.charAt(ap + 1))) {
                ca = at(a, ++ap);
            }
            while (leading && cb == '0' && bp + 1 < blen && isDig(b.charAt(bp + 1))) {
                cb = at(b, ++bp);
            }
            leading = false;
            while (isSpace(ca)) {
                ca = at(a, ++ap);
            }
            while (isSpace(cb)) {
                cb = at(b, ++bp);
            }
            if (isDig(ca) && isDig(cb)) {
                int[] pos = {ap, bp};
                int result = (ca == '0' || cb == '0') ? compareLeft(a, b, pos) : compareRight(a, b, pos);
                ap = pos[0];
                bp = pos[1];
                if (result != 0) {
                    return result;
                } else if (ap == alen && bp == blen) {
                    return 0;
                } else if (ap == alen) {
                    return -1;
                } else if (bp == blen) {
                    return 1;
                }
                ca = at(a, ap);
                cb = at(b, bp);
            }
            if (ci) {
                ca = Character.toUpperCase(ca);
                cb = Character.toUpperCase(cb);
            }
            if (ca < cb) {
                return -1;
            } else if (ca > cb) {
                return 1;
            }
            ++ap;
            ++bp;
            if (ap >= alen && bp >= blen) {
                return 0;
            } else if (ap >= alen) {
                return -1;
            } else if (bp >= blen) {
                return 1;
            }
        }
    }

    private static boolean digitEnds(String s, int i) {
        return i >= s.length() || !isDig(s.charAt(i));
    }

    private static int compareRight(String a, String b, int[] pos) {
        int bias = 0;
        for (; ; pos[0]++, pos[1]++) {
            boolean ea = digitEnds(a, pos[0]);
            boolean eb = digitEnds(b, pos[1]);
            if (ea && eb) {
                return bias;
            } else if (ea) {
                return -1;
            } else if (eb) {
                return 1;
            } else if (bias == 0) {
                bias = Integer.signum(a.charAt(pos[0]) - b.charAt(pos[1]));
            }
        }
    }

    private static int compareLeft(String a, String b, int[] pos) {
        for (; ; pos[0]++, pos[1]++) {
            boolean ea = digitEnds(a, pos[0]);
            boolean eb = digitEnds(b, pos[1]);
            if (ea && eb) {
                return 0;
            } else if (ea) {
                return -1;
            } else if (eb) {
                return 1;
            } else if (a.charAt(pos[0]) != b.charAt(pos[1])) {
                return a.charAt(pos[0]) < b.charAt(pos[1]) ? -1 : 1;
            }
        }
    }

    private static Object strtok(ContextState st, String token) {
        String subject = st.strtokSubject;
        if (subject == null) {
            return false;
        }
        int i = st.strtokPos;
        while (i < subject.length() && token.indexOf(subject.charAt(i)) >= 0) {
            i++;
        }
        if (i >= subject.length()) {
            st.strtokSubject = null;
            return false;
        }
        int start = i;
        while (i < subject.length() && token.indexOf(subject.charAt(i)) < 0) {
            i++;
        }
        st.strtokPos = Math.min(subject.length(), i + 1);
        return subject.substring(start, i);
    }

    private static Object substrReplace(Object[] a) {
        Object replace = a[1];
        Object offset = a[2];
        Object length = a.length > 3 ? a[3] : PhpNull.INSTANCE;
        if (!(a[0] instanceof PhpArray subjects)) {
            return substrReplaceOne(s(a, 0), nth(replace, 0, ""), PhpOps.toLong(nth(offset, 0, 0L)),
                    lengthOf(nth(length, 0, PhpNull.INSTANCE)));
        }
        PhpArray out = new PhpArray();
        int i = 0;
        for (Map.Entry<Object, Object> e : subjects.entries()) {
            out.put(e.getKey(), substrReplaceOne(PhpOps.toStr(e.getValue()), nth(replace, i, ""),
                    PhpOps.toLong(nth(offset, i, 0L)), lengthOf(nth(length, i, PhpNull.INSTANCE))));
            i++;
        }
        return out;
    }

    /** The i-th value of an array argument, or the scalar itself (used for every subject). */
    private static Object nth(Object v, int i, Object missing) {
        if (!(v instanceof PhpArray arr)) {
            return v;
        }
        List<Object> values = arr.values();
        return i < values.size() ? values.get(i) : missing;
    }

    private static Long lengthOf(Object v) {
        return v == PhpNull.INSTANCE ? null : PhpOps.toLong(v);
    }

    private static String substrReplaceOne(String str, Object replacement, long start, Long length) {
        int n = str.length();
        if (start < 0) {
            start = Math.max(0, n + start);
        } else if (start > n) {
            start = n;
        }
        long end;
        if (length == null) {
            end = n;
        } else if (length < 0) {
            end = Math.max(start, n + length);
        } else {
            end = Math.min(n, start + length);
        }
        return str.substring(0, (int) start) + PhpOps.toStr(replacement) + str.substring((int) end);
    }
}
