package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** URL encoding, parse_url and http_build_query. */
final class UrlFunctions {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UrlFunctions() {
    }

    static void register() {
        Builtins.def("urlencode", 1, 1, (ctx, a) -> encode(PhpOps.toStr(a[0]), false));
        Builtins.def("rawurlencode", 1, 1, (ctx, a) -> encode(PhpOps.toStr(a[0]), true));
        Builtins.def("urldecode", 1, 1, (ctx, a) -> decode(PhpOps.toStr(a[0]), true));
        Builtins.def("rawurldecode", 1, 1, (ctx, a) -> decode(PhpOps.toStr(a[0]), false));
        Builtins.def("parse_url", 1, 2, (ctx, a) -> parseUrl(PhpOps.toStr(a[0]), a.length > 1 ? PhpOps.toLong(a[1]) : -1));
        Builtins.def("http_build_query", 1, 4, (ctx, a) -> {
            if (!(a[0] instanceof PhpArray data)) {
                throw new PhpError(PhpError.TYPE_ERROR, "http_build_query(): Argument #1 ($data) must be of type array, " + PhpOps.typeName(a[0]) + " given", null);
            }
            String prefix = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toStr(a[1]) : "";
            String sep = a.length > 2 && a[2] != PhpNull.INSTANCE ? PhpOps.toStr(a[2]) : "&";
            boolean raw = a.length > 3 && PhpOps.toLong(a[3]) == 2;
            StringBuilder sb = new StringBuilder();
            buildQuery(sb, data, null, prefix, sep, raw);
            return sb.toString();
        });
    }

    /** urlencode (space as '+') or rawurlencode (RFC 3986, '~' kept), byte by byte over UTF-8. */
    static String encode(String s, boolean raw) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || (raw && c == '~')) {
                sb.append((char) c);
            } else if (c == ' ' && !raw) {
                sb.append('+');
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return sb.toString();
    }

    static String decode(String s, boolean plusIsSpace) {
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '+' && plusIsSpace) {
                out.write(' ');
            } else if (b == '%' && i + 2 < in.length && Character.digit(in[i + 1], 16) >= 0 && Character.digit(in[i + 2], 16) >= 0) {
                out.write(Character.digit(in[i + 1], 16) * 16 + Character.digit(in[i + 2], 16));
                i += 2;
            } else {
                out.write(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ parse_url

    /** The parts of a URL; null means absent (distinct from an empty query or fragment). */
    private static final class Url {
        String scheme;
        String user;
        String pass;
        String host;
        long port = -1;
        String path;
        String query;
        String fragment;
    }

    private static Object parseUrl(String str, long component) {
        Url u = parse(str);
        if (u == null) {
            return false;
        }
        if (component == -1) {
            PhpArray out = new PhpArray();
            putIf(out, "scheme", u.scheme);
            putIf(out, "host", u.host);
            if (u.port >= 0) {
                out.put("port", u.port);
            }
            putIf(out, "user", u.user);
            putIf(out, "pass", u.pass);
            putIf(out, "path", u.path);
            putIf(out, "query", u.query);
            putIf(out, "fragment", u.fragment);
            return out;
        }
        Object v = switch ((int) component) {
            case 0 -> u.scheme;
            case 1 -> u.host;
            case 2 -> u.port >= 0 ? (Object) u.port : null;
            case 3 -> u.user;
            case 4 -> u.pass;
            case 5 -> u.path;
            case 6 -> u.query;
            case 7 -> u.fragment;
            default -> throw new PhpError(PhpError.VALUE_ERROR, "parse_url(): Argument #2 ($component) must be a valid URL component identifier, " + component + " given", null);
        };
        return v == null ? PhpNull.INSTANCE : v;
    }

    private static void putIf(PhpArray out, String key, String v) {
        if (v != null) {
            out.put(key, v);
        }
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** php_replace_controlchars: control characters become '_'. */
    private static String clean(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127) {
                if (sb == null) {
                    sb = new StringBuilder(s);
                }
                sb.setCharAt(i, '_');
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static int indexOf(String s, char c, int from, int to) {
        int i = s.indexOf(c, from);
        return i >= 0 && i < to ? i : -1;
    }

    private static int lastIndexOf(String s, char c, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (s.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private static int cspan(String s, int from, int to, String chars) {
        int i = from;
        while (i < to && chars.indexOf(s.charAt(i)) < 0) {
            i++;
        }
        return i;
    }

    /** Port of php_url_parse_ex2 from ext/standard/url.c. Returns null for a seriously malformed URL. */
    private static Url parse(String str) {
        Url ret = new Url();
        int s = 0;
        int ue = str.length();
        int e = indexOf(str, ':', 0, ue);
        int p;
        String step;
        if (e > 0) {
            step = null;
            for (p = s; p < e; p++) {
                char c = str.charAt(p);
                if (!isAlpha(c) && !isDigit(c) && c != '+' && c != '.' && c != '-') {
                    if (e + 1 < ue && e < cspan(str, s, ue, "?#")) {
                        step = "port";
                    } else if (s + 1 < ue && str.charAt(s) == '/' && str.charAt(s + 1) == '/') {
                        s += 2;
                        step = "host";
                    } else {
                        step = "path";
                    }
                    break;
                }
            }
            if (step == null) {
                if (e + 1 == ue) {
                    ret.scheme = clean(str.substring(s, e));
                    return ret;
                }
                if (str.charAt(e + 1) != '/') {
                    p = e + 1;
                    while (p < ue && isDigit(str.charAt(p))) {
                        p++;
                    }
                    if ((p == ue || str.charAt(p) == '/') && (p - e) < 7) {
                        step = "port";
                    } else {
                        ret.scheme = clean(str.substring(s, e));
                        s = e + 1;
                        step = "path";
                    }
                } else {
                    ret.scheme = clean(str.substring(s, e));
                    if (e + 2 < ue && str.charAt(e + 2) == '/') {
                        s = e + 3;
                        step = "host";
                        if (ret.scheme.equalsIgnoreCase("file") && e + 3 < ue && str.charAt(e + 3) == '/') {
                            if (e + 5 < ue && str.charAt(e + 5) == ':') {
                                s = e + 4;
                            }
                            step = "path";
                        }
                    } else {
                        s = e + 1;
                        step = "path";
                    }
                }
            }
        } else if (e == 0) {
            step = "port";
        } else if (s + 1 < ue && str.charAt(s) == '/' && str.charAt(s + 1) == '/') {
            s += 2;
            step = "host";
        } else {
            step = "path";
        }

        if (step.equals("port")) {
            p = e + 1;
            int pp = p;
            while (pp < ue && pp - p < 6 && isDigit(str.charAt(pp))) {
                pp++;
            }
            if (pp - p > 0 && pp - p < 6 && (pp == ue || str.charAt(pp) == '/')) {
                long port = Long.parseLong(str.substring(p, pp));
                if (port > 65535) {
                    return null;
                }
                ret.port = port;
                if (s + 1 < ue && str.charAt(s) == '/' && str.charAt(s + 1) == '/') {
                    s += 2;
                }
                step = "host";
            } else if (p == pp && pp == ue) {
                return null;
            } else if (s + 1 < ue && str.charAt(s) == '/' && str.charAt(s + 1) == '/') {
                s += 2;
                step = "host";
            } else {
                step = "path";
            }
        }

        if (step.equals("host")) {
            e = cspan(str, s, ue, "/?#");
            p = lastIndexOf(str, '@', s, e);
            if (p >= 0) {
                int pp = indexOf(str, ':', s, p);
                if (pp >= 0) {
                    ret.user = clean(str.substring(s, pp));
                    ret.pass = clean(str.substring(pp + 1, p));
                } else {
                    ret.user = clean(str.substring(s, p));
                }
                s = p + 1;
            }
            if (s < ue && str.charAt(s) == '[' && e > 0 && str.charAt(e - 1) == ']') {
                p = -1;
            } else {
                p = lastIndexOf(str, ':', s, e);
            }
            if (p >= 0) {
                if (ret.port < 0) {
                    p++;
                    if (e - p > 5) {
                        return null;
                    } else if (e - p > 0) {
                        String digits = str.substring(p, e);
                        int k = 0;
                        while (k < digits.length() && isDigit(digits.charAt(k))) {
                            k++;
                        }
                        if (k == 0) {
                            return null;
                        }
                        long port = Long.parseLong(digits.substring(0, k));
                        if (port > 65535) {
                            return null;
                        }
                        ret.port = port;
                    }
                    p--;
                }
            } else {
                p = e;
            }
            if (p - s < 1) {
                return null;
            }
            ret.host = clean(str.substring(s, p));
            if (e == ue) {
                return ret;
            }
            s = e;
        }

        // just_path
        e = ue;
        p = indexOf(str, '#', s, e);
        if (p >= 0) {
            ret.fragment = clean(str.substring(p + 1, e));
            e = p;
        }
        p = indexOf(str, '?', s, e);
        if (p >= 0) {
            ret.query = clean(str.substring(p + 1, e));
            e = p;
        }
        if (s < e || s == ue) {
            ret.path = clean(str.substring(s, e));
        }
        return ret;
    }

    // ------------------------------------------------------------ http_build_query

    private static void buildQuery(StringBuilder sb, PhpArray data, String parent, String numericPrefix, String sep, boolean raw) {
        for (Map.Entry<Object, Object> e : data.entries()) {
            Object v = e.getValue();
            if (v == PhpNull.INSTANCE) {
                continue;
            }
            String key = PhpOps.toStr(e.getKey());
            String name;
            if (parent == null) {
                name = (e.getKey() instanceof Long ? numericPrefix + key : encode(key, raw));
                if (e.getKey() instanceof Long) {
                    name = encode(name, raw);
                }
            } else {
                name = parent + "%5B" + encode(key, raw) + "%5D";
            }
            if (v instanceof PhpArray inner) {
                buildQuery(sb, inner, name, numericPrefix, sep, raw);
                continue;
            }
            String value;
            if (v instanceof Boolean b) {
                value = b ? "1" : "0";
            } else if (v instanceof Double d) {
                value = PhpOps.formatDouble(d, 17);
            } else {
                value = PhpOps.toStr(v);
            }
            if (!sb.isEmpty()) {
                sb.append(sep);
            }
            sb.append(name).append('=').append(encode(value, raw));
        }
    }
}
