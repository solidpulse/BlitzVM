package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpQuery;
import dev.phpruntime.runtime.PhpRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** setcookie, setrawcookie, header_remove, headers_list, headers_sent and parse_str. */
final class HttpFunctions {

    private static final DateTimeFormatter COOKIE_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private HttpFunctions() {
    }

    static void register() {
        Builtins.def("setcookie", 1, 7, (ctx, a) -> setcookie(ctx, a, true, "setcookie"));
        Builtins.def("setrawcookie", 1, 7, (ctx, a) -> setcookie(ctx, a, false, "setrawcookie"));
        Builtins.def("header_remove", 0, 1, (ctx, a) -> {
            PhpRequest req = ctx.request();
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                req.responseHeaders.clear();
            } else {
                String name = PhpOps.toStr(a[0]);
                req.responseHeaders.removeIf(h -> h[0].equalsIgnoreCase(name));
            }
            return PhpNull.INSTANCE;
        });
        Builtins.def("headers_list", 0, 0, (ctx, a) -> {
            PhpArray out = new PhpArray();
            for (String[] h : ctx.request().responseHeaders) {
                out.append(h[0] + ": " + h[1]);
            }
            return out;
        });
        Builtins.def("headers_sent", 0, 2, (ctx, a) -> ctx.outputStarted());
        Builtins.defRef("parse_str", 2, 2, 2, (ctx, a) -> {
            a[1] = PhpQuery.parseQuery(PhpOps.toStr(a[0]));
            return PhpNull.INSTANCE;
        });
    }

    /**
     * The header line trimmed as php trims it (trailing spaces, tabs, CR, LF, vertical tab and
     * form feed), or null after php's warning when the line must not be sent. NUL is checked
     * first, then a CR or LF that is not trailing whitespace.
     */
    static String checkedHeaderLine(PhpContext ctx, String line) {
        if (line.indexOf('\0') >= 0) {
            ctx.warning("Header may not contain NUL bytes", headerWarningLine(ctx));
            return null;
        }
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000b' || c == '\f') {
                end--;
            } else {
                break;
            }
        }
        String trimmed = end == line.length() ? line : line.substring(0, end);
        if (trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
            ctx.warning("Header may not contain more than a single header, new line detected", headerWarningLine(ctx));
            return null;
        }
        return trimmed;
    }

    private static int headerWarningLine(PhpContext ctx) {
        return dev.phpruntime.runtime.PhpBacktrace.callSiteLine(ctx.currentBuiltinCallSite());
    }

    private static Object setcookie(PhpContext ctx, Object[] a, boolean encode, String fn) {
        String name = PhpOps.toStr(a[0]);
        String value = a.length > 1 ? PhpOps.toStr(a[1]) : "";
        long expires = 0;
        String path = "";
        String domain = "";
        boolean secure = false;
        boolean httpOnly = false;
        String sameSite = "";
        if (a.length > 2 && a[2] instanceof PhpArray opts) {
            if (a.length > 3) {
                throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Expects exactly 3 arguments when argument #3 ($expires_or_options) is an array", null);
            }
            for (var e : opts.entries()) {
                String key = dev.phpruntime.runtime.PhpStrings.lower(PhpOps.toStr(e.getKey()));
                Object v = e.getValue();
                switch (key) {
                    case "expires" -> expires = PhpOps.toLong(v);
                    case "path" -> path = PhpOps.toStr(v);
                    case "domain" -> domain = PhpOps.toStr(v);
                    case "secure" -> secure = PhpOps.toBool(v);
                    case "httponly" -> httpOnly = PhpOps.toBool(v);
                    case "samesite" -> sameSite = PhpOps.toStr(v);
                    default -> throw new PhpError("ValueError", fn + "(): option \"" + PhpOps.toStr(e.getKey()) + "\" is invalid", null);
                }
            }
        } else {
            expires = a.length > 2 ? PhpOps.toLong(a[2]) : 0;
            path = a.length > 3 ? PhpOps.toStr(a[3]) : "";
            domain = a.length > 4 ? PhpOps.toStr(a[4]) : "";
            secure = a.length > 5 && PhpOps.toBool(a[5]);
            httpOnly = a.length > 6 && PhpOps.toBool(a[6]);
        }
        if (name.isEmpty()) {
            throw new PhpError("ValueError", fn + "(): Argument #1 ($name) cannot be empty", null);
        }
        if (name.chars().anyMatch(c -> "=,; \t\r\n\u000b\f".indexOf(c) >= 0)) {
            throw new PhpError("ValueError", fn + "(): Argument #1 ($name) cannot contain \"=\", \",\", \";\", \" \", \"\\t\", \"\\r\", \"\\n\", \"\\013\", or \"\\014\"", null);
        }
        if (!encode && value.chars().anyMatch(c -> ",; \t\r\n\u000b\f".indexOf(c) >= 0)) {
            throw new PhpError("ValueError", fn + "(): Argument #2 ($value) cannot contain \",\", \";\", \" \", \"\\t\", \"\\r\", \"\\n\", \"\\013\", or \"\\014\"", null);
        }
        checkCookieAttribute(fn, "path", path);
        checkCookieAttribute(fn, "domain", domain);
        StringBuilder h = new StringBuilder(name).append('=');
        if (value.isEmpty()) {
            // An empty value deletes the cookie.
            h.append("deleted; expires=Thu, 01 Jan 1970 00:00:01 GMT; Max-Age=0");
        } else {
            h.append(encode ? UrlFunctions.encode(value, true) : value);
            if (expires > 0) {
                long maxAge = Math.max(0, expires - System.currentTimeMillis() / 1000);
                h.append("; expires=").append(COOKIE_DATE.format(Instant.ofEpochSecond(expires)))
                        .append("; Max-Age=").append(maxAge);
            }
        }
        if (!path.isEmpty()) {
            h.append("; path=").append(path);
        }
        if (!domain.isEmpty()) {
            h.append("; domain=").append(domain);
        }
        if (secure) {
            h.append("; secure");
        }
        if (httpOnly) {
            h.append("; HttpOnly");
        }
        if (!sameSite.isEmpty()) {
            h.append("; SameSite=").append(sameSite);
        }
        String checked = checkedHeaderLine(ctx, h.toString());
        if (checked == null) {
            return false;
        }
        ctx.request().setHeader("Set-Cookie", checked, false);
        return true;
    }

    /** Path and domain are attribute values php rejects before the header is built. */
    private static void checkCookieAttribute(String fn, String option, String value) {
        if (value.chars().anyMatch(c -> ",; \t\r\n\u000b\f".indexOf(c) >= 0)) {
            throw new PhpError("ValueError", fn + "(): \"" + option
                    + "\" option cannot contain \",\", \";\", \" \", \"\\t\", \"\\r\", \"\\n\", \"\\013\", or \"\\014\"", null);
        }
    }
}
