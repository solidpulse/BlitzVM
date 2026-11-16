package dev.phpruntime.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PHP's request-variable parsing, used by parse_str, $_GET, $_POST and $_COOKIE: "a[b][]=1"
 * builds nested arrays, "." and " " in the top-level name become "_", and an unmatched "["
 * becomes "_" too.
 */
public final class PhpQuery {

    private PhpQuery() {
    }

    /** parse_str, $_GET and urlencoded $_POST: "&"-separated, names and values urldecoded ("+" is a space). */
    public static PhpArray parseQuery(String data) {
        PhpArray out = new PhpArray();
        if (data == null || data.isEmpty()) {
            return out;
        }
        for (String pair : data.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = urldecode(eq < 0 ? pair : pair.substring(0, eq), true);
            String value = eq < 0 ? "" : urldecode(pair.substring(eq + 1), true);
            register(out, name, value, false);
        }
        return out;
    }

    /** $_COOKIE: ";"-separated, names kept as sent, values rawurldecoded, the first of a name wins. */
    public static PhpArray parseCookies(String header) {
        PhpArray out = new PhpArray();
        if (header == null || header.isEmpty()) {
            return out;
        }
        for (String pair : header.split(";")) {
            String p = pair.stripLeading();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            String name = eq < 0 ? p : p.substring(0, eq);
            String value = eq < 0 ? "" : urldecode(p.substring(eq + 1), false);
            register(out, name, value, true);
        }
        return out;
    }

    /** php_register_variable_ex: stores value under a possibly nested name. */
    public static void register(PhpArray target, String name, String value, boolean keepExisting) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == ' ') {
            i++;
        }
        name = name.substring(i);
        int bracket = name.indexOf('[');
        String base = bracket < 0 ? name : name.substring(0, bracket);
        List<String> indexes = new ArrayList<>();
        if (bracket >= 0) {
            int pos = bracket;
            while (pos < name.length() && name.charAt(pos) == '[') {
                int close = name.indexOf(']', pos + 1);
                if (close < 0) {
                    if (indexes.isEmpty()) {
                        // "a[b" is the plain name "a_b"
                        base = base + "_" + name.substring(pos + 1);
                    }
                    break;
                }
                indexes.add(name.substring(pos + 1, close));
                pos = close + 1;
            }
        }
        StringBuilder clean = new StringBuilder(base.length());
        for (int k = 0; k < base.length(); k++) {
            char c = base.charAt(k);
            clean.append(c == '.' || c == ' ' ? '_' : c);
        }
        base = clean.toString();
        if (base.isEmpty()) {
            return;
        }
        if (indexes.isEmpty()) {
            if (!keepExisting || !target.containsKey(base)) {
                target.put(base, value);
            }
            return;
        }
        // Walk down, creating arrays as needed; a null key means "append" ([]).
        PhpArray current = target;
        Object key = base;
        for (String idx : indexes) {
            Object existing = key == null ? null : current.get(key);
            PhpArray child = existing instanceof PhpArray arr ? arr.forWrite() : new PhpArray();
            if (key == null) {
                current.append(child);
            } else {
                current.put(key, child);
            }
            current = child;
            key = idx.isEmpty() ? null : idx;
        }
        if (key == null) {
            current.append(value);
        } else if (!keepExisting || !current.containsKey(key)) {
            current.put(key, value);
        }
    }

    /** urldecode (plusAsSpace) or rawurldecode, byte-wise, invalid escapes kept literally. */
    public static String urldecode(String s, boolean plusAsSpace) {
        if (s.indexOf('%') < 0 && (!plusAsSpace || s.indexOf('+') < 0)) {
            return s;
        }
        byte[] in = PhpStrings.bytes(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '+' && plusAsSpace) {
                out.write(' ');
            } else if (b == '%' && i + 2 < in.length && hex(in[i + 1]) >= 0 && hex(in[i + 2]) >= 0) {
                out.write(hex(in[i + 1]) * 16 + hex(in[i + 2]));
                i += 2;
            } else {
                out.write(b);
            }
        }
        return PhpStrings.of(out.toByteArray());
    }

    private static int hex(byte b) {
        int c = b & 255;
        return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10
                : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    }

    /** Case-insensitive header lookup in the request's header map. */
    public static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
