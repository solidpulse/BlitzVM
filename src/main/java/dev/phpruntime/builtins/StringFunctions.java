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

/** String functions, sprintf, number_format and json_encode. */
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
                throw new PhpError("str_repeat(): Argument #2 ($times) must be greater than or equal to 0");
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
                throw new PhpError("explode(): Argument #1 ($separator) cannot be empty");
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
        Builtins.def("urlencode", 1, 1, (ctx, a) -> java.net.URLEncoder.encode(s(a, 0), StandardCharsets.UTF_8));
        Builtins.def("rawurlencode", 1, 1, (ctx, a) -> java.net.URLEncoder.encode(s(a, 0), StandardCharsets.UTF_8).replace("+", "%20"));
        Builtins.def("urldecode", 1, 1, (ctx, a) -> java.net.URLDecoder.decode(s(a, 0), StandardCharsets.UTF_8));
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
            throw new PhpError("implode(): Argument #2 ($array) must be of type ?array, " + PhpOps.typeName(a[1]) + " given");
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
                throw new PhpError("Missing format specifier at end of string");
            }
            char spec = fmt.charAt(i++);
            int idx = argnum > 0 ? argnum : argIndex++;
            if (idx >= a.length) {
                throw new PhpError((idx + 1) + " arguments are required, " + a.length + " given");
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
                default -> throw new PhpError("Unknown format specifier \"" + spec + "\"");
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
}
