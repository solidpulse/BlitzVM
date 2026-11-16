package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * filter_var and the filter extension. WordPress uses the email, IP, URL and boolean
 * validators; the remaining validate and sanitize filters are provided for compatibility.
 */
final class FilterFunctions {

    private static final long FLAG_ALLOW_OCTAL = 1L;
    private static final long FLAG_ALLOW_HEX = 2L;
    private static final long FLAG_STRIP_LOW = 4L;
    private static final long FLAG_STRIP_HIGH = 8L;
    private static final long FLAG_ENCODE_LOW = 16L;
    private static final long FLAG_ENCODE_HIGH = 32L;
    private static final long FLAG_ENCODE_AMP = 64L;
    private static final long FLAG_NO_ENCODE_QUOTES = 128L;
    private static final long FLAG_EMPTY_STRING_NULL = 256L;
    private static final long FLAG_STRIP_BACKTICK = 512L;
    private static final long FLAG_ALLOW_FRACTION = 4096L;
    private static final long FLAG_ALLOW_THOUSAND = 8192L;
    private static final long FLAG_ALLOW_SCIENTIFIC = 16384L;
    private static final long FLAG_PATH_REQUIRED = 262144L;
    private static final long FLAG_QUERY_REQUIRED = 524288L;
    private static final long FLAG_IPV4 = 1048576L;
    private static final long FLAG_IPV6 = 2097152L;
    private static final long FLAG_NO_RES_RANGE = 4194304L;
    private static final long FLAG_NO_PRIV_RANGE = 8388608L;
    private static final long FLAG_GLOBAL_RANGE = 536870912L;
    private static final long FLAG_EMAIL_UNICODE = 1048576L;
    private static final long FLAG_HOSTNAME = 1048576L;
    private static final long FLAG_NULL_ON_FAILURE = 134217728L;
    private static final long FLAG_REQUIRE_ARRAY = 16777216L;
    private static final long FLAG_REQUIRE_SCALAR = 33554432L;
    private static final long FLAG_FORCE_ARRAY = 67108864L;

    private FilterFunctions() {
    }

    static void register() {
        Builtins.def("filter_var", 1, 3, FilterFunctions::filterVar);
    }

    /** The filter id and flags/options resolved the way php's filter_var does. */
    private record Options(long filter, long flags, PhpArray options) {
        Object option(String name) {
            return options == null ? null : options.get(name);
        }
    }

    private static Options parseOptions(Object[] a) {
        long filter = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toLong(a[1]) : 516L;
        long flags = 0;
        PhpArray options = null;
        if (a.length > 2 && a[2] != PhpNull.INSTANCE) {
            if (a[2] instanceof PhpArray arr) {
                options = arr;
                Object nested = arr.get("options");
                if (nested instanceof PhpArray inner) {
                    options = inner;
                }
                Object f = arr.get("flags");
                if (f == null || f == PhpNull.INSTANCE) {
                    f = options.get("flags");
                }
                if (f != null && f != PhpNull.INSTANCE) {
                    flags = PhpOps.toLong(f);
                }
            } else {
                flags = PhpOps.toLong(a[2]);
            }
        }
        return new Options(filter, flags, options);
    }

    private static Object filterVar(PhpContext ctx, Object[] a) {
        Options opt = parseOptions(a);
        Object input = a.length > 0 ? a[0] : PhpNull.INSTANCE;
        return filterValue(ctx, input, opt);
    }

    private static Object filterValue(PhpContext ctx, Object input, Options opt) {
        boolean requireArray = (opt.flags & FLAG_REQUIRE_ARRAY) != 0;
        boolean requireScalar = (opt.flags & FLAG_REQUIRE_SCALAR) != 0;
        boolean forceArray = (opt.flags & FLAG_FORCE_ARRAY) != 0;

        if (input instanceof PhpArray arr) {
            if (!requireArray) {
                return false;
            }
            PhpArray out = new PhpArray();
            for (Map.Entry<Object, Object> e : arr.entries()) {
                Object v = filterScalar(ctx, e.getValue(), opt);
                if (v instanceof PhpArray nested && requireArray) {
                    // php does not recurse into nested arrays for REQUIRE_ARRAY.
                    return false;
                }
                out.put(e.getKey(), v);
            }
            return out;
        }
        if (requireArray) {
            return PhpNull.INSTANCE;
        }
        if (requireScalar && (input instanceof PhpArray || input instanceof dev.phpruntime.runtime.PhpObject)) {
            return false;
        }
        Object result = filterScalar(ctx, input, opt);
        if (forceArray) {
            PhpArray arr = new PhpArray();
            arr.append(result);
            return arr;
        }
        return result;
    }

    /** Runs one filter over one scalar value. */
    private static Object filterScalar(PhpContext ctx, Object input, Options opt) {
        long filter = opt.filter;
        Object value = input;

        if (filter == 516L) { // FILTER_DEFAULT / FILTER_UNSAFE_RAW
            Object raw = unsafeRaw(toStringValue(value), opt.flags);
            return raw == null ? PhpNull.INSTANCE : raw;
        }
        if (filter == 1024L) { // FILTER_CALLBACK
            Object callback = opt.option("options");
            PhpCallable cb = callback == null ? null : PhpCallable.resolveOrNull(ctx, callback);
            if (cb == null) {
                return failure(PhpNull.INSTANCE, opt);
            }
            return cb.call(ctx, null, null, value);
        }

        switch ((int) filter) {
            case 257 -> {
                return validateInt(value, opt);
            }
            case 258 -> {
                Boolean b = validateBoolean(value);
                return b != null ? b : failure(PhpNull.INSTANCE, opt);
            }
            case 259 -> {
                Double d = validateFloat(value);
                if (d == null) {
                    return failure(PhpNull.INSTANCE, opt);
                }
                double min = rangeOption(opt, "min_range", Double.NaN);
                double max = rangeOption(opt, "max_range", Double.NaN);
                if (!Double.isNaN(min) && d < min || !Double.isNaN(max) && d > max) {
                    return failure(PhpNull.INSTANCE, opt);
                }
                return d;
            }
            case 272 -> {
                Object re = opt.option("regexp");
                if (!(re instanceof String pattern)) {
                    return failure(PhpNull.INSTANCE, opt);
                }
                String s = toStringValue(value);
                try {
                    return Pattern.compile(PhpStrings.toJava(pattern)).matcher(s).find() ? s : failure(PhpNull.INSTANCE, opt);
                } catch (RuntimeException e) {
                    return false;
                }
            }
            case 273 -> {
                String url = toStringValue(value);
                return validateUrl(url, opt.flags) ? url : failure(PhpNull.INSTANCE, opt);
            }
            case 274 -> {
                String email = toStringValue(value);
                return validateEmail(email, (opt.flags & FLAG_EMAIL_UNICODE) != 0) ? email : failure(PhpNull.INSTANCE, opt);
            }
            case 275 -> {
                String ip = toStringValue(value);
                return validateIp(ip, opt.flags) ? ip : failure(PhpNull.INSTANCE, opt);
            }
            case 276 -> {
                String mac = toStringValue(value);
                return validateMac(mac) ? mac : failure(PhpNull.INSTANCE, opt);
            }
            case 277 -> {
                String domain = toStringValue(value);
                return validateDomain(domain, (opt.flags & FLAG_HOSTNAME) != 0) ? domain : failure(PhpNull.INSTANCE, opt);
            }
            default -> {
                if (filter >= 512 && filter <= 523) {
                    return sanitize((int) filter, toStringValue(value), opt.flags);
                }
                throw new PhpError("filter_var(): Unknown filter with ID " + filter, null);
            }
        }
    }

    private static Object failure(Object value, Options opt) {
        if ((opt.flags & FLAG_NULL_ON_FAILURE) != 0) {
            return PhpNull.INSTANCE;
        }
        Object def = opt.option("default");
        if (def != null) {
            return PhpOps.copyOnAssign(def);
        }
        return false;
    }

    private static Object failureInt(Options opt) {
        return failure(PhpNull.INSTANCE, opt);
    }

    private static String toStringValue(Object v) {
        return v == PhpNull.INSTANCE ? "" : PhpOps.toStr(v);
    }

    // ------------------------------------------------------------------ validators

    private static Object validateInt(Object value, Options opt) {
        long flags = opt.flags;
        Object allowOctal = opt.option("flags");
        if (allowOctal != null && allowOctal != PhpNull.INSTANCE) {
            flags = PhpOps.toLong(allowOctal);
        }
        Long number = null;
        if (value instanceof Long l) {
            number = l;
        } else if (value instanceof Boolean b) {
            number = b ? 1L : null;
        } else if (value instanceof Double d) {
            if (d == Math.rint(d) && !d.isInfinite() && Math.abs(d) <= 9.223372036854775807E18) {
                number = d.longValue();
            }
        } else if (value instanceof String s) {
            String t = s.strip();
            if (!t.isEmpty()) {
                int base = 10;
                String body = t;
                int sign = 1;
                if (body.startsWith("+") || body.startsWith("-")) {
                    if (body.startsWith("-")) {
                        sign = -1;
                    }
                    body = body.substring(1);
                }
                if ((flags & FLAG_ALLOW_HEX) != 0 && (body.startsWith("0x") || body.startsWith("0X"))) {
                    base = 16;
                    body = body.substring(2);
                } else if ((flags & FLAG_ALLOW_OCTAL) != 0 && body.length() > 1 && body.startsWith("0")) {
                    base = 8;
                    body = body.substring(1);
                } else if (body.length() > 1 && body.charAt(0) == '0') {
                    return failureInt(opt); // leading zeros are not canonical decimal
                }
                if (!body.isEmpty()) {
                    final int digitBase = base;
                    boolean allDigits = body.chars().allMatch(c -> Character.digit(c, digitBase) >= 0);
                    if (allDigits) {
                        try {
                            number = Math.multiplyExact(Long.parseLong(body, base), (long) sign);
                        } catch (NumberFormatException | ArithmeticException e) {
                            number = null;
                        }
                    }
                }
            }
        }
        if (number == null) {
            return failureInt(opt);
        }
        Long min = longOption(opt, "min_range");
        Long max = longOption(opt, "max_range");
        if (min != null && number < min || max != null && number > max) {
            return failureInt(opt);
        }
        return number;
    }

    private static Boolean validateBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Long l) {
            if (l == 1) {
                return true;
            }
            if (l == 0) {
                return false;
            }
            return null;
        }
        if (value instanceof Double d) {
            if (d == 1.0) {
                return true;
            }
            if (d == 0.0) {
                return false;
            }
            return null;
        }
        String s = PhpStrings.lower(toStringValue(value));
        return switch (s) {
            case "1", "true", "on", "yes" -> true;
            case "0", "false", "off", "no", "" -> false;
            default -> null;
        };
    }

    private static Double validateFloat(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : null;
        }
        String s = toStringValue(value).strip();
        if (s.isEmpty()) {
            return null;
        }
        // C strtod accepts .5, 5., exponents and a leading sign, but not thousands separators.
        if (!s.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean validateEmail(String email, boolean unicode) {
        if (email.isEmpty() || email.length() > 320) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return false;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (local.length() > 64) {
            return false;
        }
        if (!validLocalPart(local, unicode)) {
            return false;
        }
        return validDomain(domain, unicode, true);
    }

    private static boolean validLocalPart(String local, boolean unicode) {
        if (local.startsWith("\"")) {
            // php's email filter does not accept quoted local parts.
            return false;
        }
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c >= 128) {
                if (!unicode) {
                    return false;
                }
                continue;
            }
            if (Character.isLetterOrDigit(c) || "!#$%&'*+-/=?^_`{|}~.".indexOf(c) >= 0) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean validDomain(String domain, boolean unicode, boolean requireDot) {
        if (domain.isEmpty() || domain.length() > 255) {
            return false;
        }
        if (domain.startsWith("[") && domain.endsWith("]")) {
            String literal = domain.substring(1, domain.length() - 1);
            if (literal.startsWith("IPv6:")) {
                return parseIpv6(literal.substring(5)) != null;
            }
            return parseIpv4(literal) != null;
        }
        if (requireDot && domain.indexOf('.') < 0) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (c >= 128) {
                    if (!unicode) {
                        return false;
                    }
                    continue;
                }
                if (!Character.isLetterOrDigit(c) && c != '-') {
                    return false;
                }
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    private static boolean validateDomain(String domain, boolean hostname) {
        return validDomain(domain, false, false);
    }

    private static boolean validateMac(String mac) {
        return mac.matches("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}");
    }

    /** URL parsing close to php_url_parse_ex plus filter_var's scheme/host rules. */
    private static boolean validateUrl(String url, long flags) {
        if (url.isEmpty()) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '"' || c == '<' || c == '>' || c == '\\') {
                return false;
            }
        }
        int colon = url.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String scheme = url.substring(0, colon);
        if (!scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            return false;
        }
        String rest = url.substring(colon + 1);
        boolean hasHost = false;
        boolean hasPath;
        boolean hasQuery = false;
        if (rest.startsWith("//")) {
            int authorityEnd = rest.length();
            for (int i = 2; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    authorityEnd = i;
                    break;
                }
            }
            String authority = rest.substring(2, authorityEnd);
            int at = authority.lastIndexOf('@');
            String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
            if (!validAuthority(hostPort, scheme)) {
                return false;
            }
            hasHost = !hostPart(hostPort).isEmpty();
            String afterAuthority = rest.substring(authorityEnd);
            int q = afterAuthority.indexOf('?');
            hasPath = !afterAuthority.isEmpty() && afterAuthority.charAt(0) == '/' && q != 0;
            if (afterAuthority.startsWith("/")) {
                int end = afterAuthority.length();
                int h = afterAuthority.indexOf('#');
                if (h >= 0) {
                    end = h;
                }
                int question = afterAuthority.indexOf('?');
                if (question >= 0) {
                    end = Math.min(end, question);
                }
                hasPath = end > 1; // "/" alone counts as a path in php
                if (end == 1) {
                    hasPath = true;
                }
            }
            hasQuery = afterAuthority.indexOf('?') >= 0;
        } else {
            hasPath = !rest.isEmpty();
            hasQuery = rest.indexOf('?') >= 0;
            if (!hasPath || !(scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("news")
                    || scheme.equalsIgnoreCase("file"))) {
                return false;
            }
            hasHost = scheme.equalsIgnoreCase("file");
        }
        if (!hasHost && !(scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("news")
                || scheme.equalsIgnoreCase("file"))) {
            return false;
        }
        if ((flags & FLAG_PATH_REQUIRED) != 0 && !hasPath) {
            return false;
        }
        if ((flags & FLAG_QUERY_REQUIRED) != 0 && !hasQuery) {
            return false;
        }
        return true;
    }

    private static String hostPart(String hostPort) {
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            return close < 0 ? "" : hostPort.substring(1, close);
        }
        int colon = hostPort.lastIndexOf(':');
        return colon < 0 ? hostPort : hostPort.substring(0, colon);
    }

    private static boolean validAuthority(String hostPort, String scheme) {
        if (hostPort.isEmpty()) {
            return scheme.equalsIgnoreCase("file");
        }
        String host;
        String port = null;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = hostPort.substring(1, close);
            if (parseIpv6(host) == null) {
                return false;
            }
            String tail = hostPort.substring(close + 1);
            if (!tail.isEmpty()) {
                if (!tail.startsWith(":")) {
                    return false;
                }
                port = tail.substring(1);
            }
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                port = hostPort.substring(colon + 1);
            } else {
                host = hostPort;
            }
            if (host.isEmpty()) {
                return false;
            }
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_') {
                    return false;
                }
            }
        }
        if (port != null && !port.isEmpty()) {
            if (!port.chars().allMatch(Character::isDigit) || port.length() > 5) {
                return false;
            }
            int p = Integer.parseInt(port);
            if (p > 65535) {
                return false;
            }
        }
        return true;
    }

    private static boolean validateIp(String ip, long flags) {
        int[] v4 = parseIpv4(ip);
        int[] v6 = v4 == null ? parseIpv6(ip) : null;
        boolean isV4 = v4 != null;
        boolean isV6 = v6 != null;
        if (!isV4 && !isV6) {
            return false;
        }
        if ((flags & (FLAG_IPV4 | FLAG_IPV6)) != 0) {
            if (isV4 && (flags & FLAG_IPV4) == 0) {
                return false;
            }
            if (isV6 && (flags & FLAG_IPV6) == 0) {
                return false;
            }
        }
        if ((flags & FLAG_NO_PRIV_RANGE) != 0) {
            if (isV4 && isPrivateV4(v4)) {
                return false;
            }
            if (isV6 && isPrivateV6(v6)) {
                return false;
            }
        }
        if ((flags & FLAG_NO_RES_RANGE) != 0) {
            if (isV4 && isReservedV4(v4)) {
                return false;
            }
            if (isV6 && isReservedV6(v6)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrivateV4(int[] b) {
        return b[0] == 10
                || b[0] == 172 && (b[1] & 0xf0) == 16
                || b[0] == 192 && b[1] == 168;
    }

    private static boolean isReservedV4(int[] b) {
        return b[0] == 0
                || b[0] == 127
                || b[0] == 169 && b[1] == 254
                || (b[0] & 0xf0) == 240;
    }

    private static boolean isPrivateV6(int[] b) {
        return (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isReservedV6(int[] b) {
        boolean zeroPrefix = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                zeroPrefix = false;
                break;
            }
        }
        if (zeroPrefix) {
            // ::, ::1 and IPv4-mapped ::ffff:0:0/96
            if (b[10] == 0 && b[11] == 0 && b[12] == 0 && b[13] == 0 && b[14] == 0 && b[15] == 0) {
                return true;
            }
            if (b[10] == 0 && b[11] == 0 && b[12] == 0 && b[13] == 0 && b[14] == 0 && b[15] == 1) {
                return true;
            }
            if (b[10] == 0 && b[11] == 0 && b[12] == 0 && b[13] == 0 && b[14] == 0xff && b[15] == 0xff) {
                return true;
            }
        }
        // IPv4-mapped addresses are reserved as a whole range.
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && b[10] == 0xff && b[11] == 0xff) {
            return true;
        }
        return (b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80; // fe80::/10
    }

    private static int[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || p.charAt(0) == '0' && p.length() > 1) {
                return null;
            }
            if (!p.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int v = Integer.parseInt(p);
            if (v > 255) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    private static int[] parseIpv6(String s) {
        if (s.isEmpty() || s.indexOf('%') >= 0) {
            return null;
        }
        int doubleColon = s.indexOf("::");
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0) {
            return null;
        }
        String head = doubleColon >= 0 ? s.substring(0, doubleColon) : s;
        String tail = doubleColon >= 0 ? s.substring(doubleColon + 2) : "";
        int[] headGroups = parseIpv6Groups(head);
        int[] tailGroups = parseIpv6Groups(tail);
        if (headGroups == null || tailGroups == null) {
            return null;
        }
        int total = headGroups.length + tailGroups.length;
        java.util.List<Integer> groups = new java.util.ArrayList<>();
        if (doubleColon >= 0) {
            if (total > 8) {
                return null;
            }
        } else if (total != 8) {
            return null;
        }
        for (int g : headGroups) {
            groups.add(g);
        }
        if (doubleColon >= 0) {
            for (int i = total; i < 8; i++) {
                groups.add(0);
            }
        }
        for (int g : tailGroups) {
            groups.add(g);
        }
        if (groups.size() != 8) {
            return null;
        }
        int[] out = new int[16];
        for (int i = 0; i < 8; i++) {
            int g = groups.get(i);
            out[i * 2] = g >> 8;
            out[i * 2 + 1] = g & 0xff;
        }
        return out;
    }

    /** Splits a colon-separated IPv6 section; the last part may be an embedded IPv4 address. */
    private static int[] parseIpv6Groups(String section) {
        if (section.isEmpty()) {
            return new int[0];
        }
        String[] parts = section.split(":", -1);
        java.util.List<Integer> groups = new java.util.ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                return null;
            }
            boolean last = i == parts.length - 1;
            if (last && p.indexOf('.') >= 0) {
                int[] v4 = parseIpv4(p);
                if (v4 == null) {
                    return null;
                }
                groups.add(v4[0] << 8 | v4[1]);
                groups.add(v4[2] << 8 | v4[3]);
                continue;
            }
            if (p.length() > 4) {
                return null;
            }
            for (int j = 0; j < p.length(); j++) {
                if (Character.digit(p.charAt(j), 16) < 0) {
                    return null;
                }
            }
            groups.add(Integer.parseInt(p, 16));
        }
        int[] out = new int[groups.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = groups.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ sanitizers

    private static Object sanitize(int filter, String s, long flags) {
        switch (filter) {
            case 513, 522 -> { // FILTER_SANITIZE_STRING (deprecated), FULL_SPECIAL_CHARS
                if (filter == 522) {
                    return HtmlEntities.encode(s, 3, "UTF-8", true, false);
                }
                StringBuilder sb = new StringBuilder(s.length());
                boolean inTag = false;
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '<') {
                        inTag = true;
                    } else if (c == '>' && inTag) {
                        inTag = false;
                    } else if (!inTag && c != 0) {
                        sb.append(c);
                    }
                }
                return stripFlags(sb.toString(), flags);
            }
            case 514 -> { // FILTER_SANITIZE_ENCODED
                StringBuilder sb = new StringBuilder(s.length() * 3);
                for (int i = 0; i < s.length(); i++) {
                    char c = (char) (s.charAt(i) & 0xff);
                    if (Character.isLetterOrDigit(c) || "-_.!~*'()".indexOf(c) >= 0) {
                        sb.append((char) c);
                    } else {
                        sb.append('%').append(String.format("%02X", (int) c));
                    }
                }
                return sb.toString();
            }
            case 515 -> { // FILTER_SANITIZE_SPECIAL_CHARS
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '<' || c == '>' || c == '&' || c == '"' || c == '\'') {
                        sb.append("&#").append((int) c).append(';');
                    } else if (!stripFlags(flags, c)) {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            case 517 -> { // FILTER_SANITIZE_EMAIL
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (Character.isLetterOrDigit(c) || "!#$%&'*+-/=?^_`{|}~@.[]".indexOf(c) >= 0) {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            case 518 -> { // FILTER_SANITIZE_URL
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (Character.isLetterOrDigit(c) || "-_.!~*'()=:/?#[]@!$&+,;%".indexOf(c) >= 0) {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            case 519 -> { // FILTER_SANITIZE_NUMBER_INT
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (Character.isDigit(c) || c == '+' || c == '-') {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            case 520 -> { // FILTER_SANITIZE_NUMBER_FLOAT
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (Character.isDigit(c) || c == '+' || c == '-'
                            || (flags & FLAG_ALLOW_FRACTION) != 0 && c == '.'
                            || (flags & FLAG_ALLOW_SCIENTIFIC) != 0 && (c == 'e' || c == 'E')) {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            case 523 -> { // FILTER_SANITIZE_ADD_SLASHES
                return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                        .replace("\0", "\\0");
            }
            default -> {
                return s;
            }
        }
    }

    private static boolean stripFlags(long flags, char c) {
        return (flags & FLAG_STRIP_LOW) != 0 && c < 32
                || (flags & FLAG_STRIP_HIGH) != 0 && c >= 128
                || (flags & FLAG_STRIP_BACKTICK) != 0 && c == '`';
    }

    private static String stripFlags(String s, long flags) {
        if (flags == 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!stripFlags(flags, c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unsafeRaw(String s, long flags) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (stripFlags(flags, c)) {
                continue;
            }
            if ((flags & FLAG_ENCODE_LOW) != 0 && c < 32
                    || (flags & FLAG_ENCODE_HIGH) != 0 && c >= 128
                    || (flags & FLAG_ENCODE_AMP) != 0 && c == '&') {
                sb.append("&#").append((int) c).append(';');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString();
        if (out.isEmpty() && (flags & FLAG_EMPTY_STRING_NULL) != 0) {
            return null;
        }
        return out;
    }

    private static Long longOption(Options opt, String name) {
        Object v = opt.option(name);
        return v == null || v == PhpNull.INSTANCE ? null : PhpOps.toLong(v);
    }

    private static double rangeOption(Options opt, String name, double fallback) {
        Long v = longOption(opt, name);
        return v == null ? fallback : v;
    }
}
