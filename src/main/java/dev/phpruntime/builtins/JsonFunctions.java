package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.util.Map;

/** json_encode, json_decode, json_last_error, json_last_error_msg, matching ext/json's output. */
final class JsonFunctions {

    static final long HEX_TAG = 1;
    static final long HEX_AMP = 2;
    static final long HEX_APOS = 4;
    static final long HEX_QUOT = 8;
    static final long FORCE_OBJECT = 16;
    static final long NUMERIC_CHECK = 32;
    static final long UNESCAPED_SLASHES = 64;
    static final long PRETTY_PRINT = 128;
    static final long UNESCAPED_UNICODE = 256;
    static final long PARTIAL_OUTPUT_ON_ERROR = 512;
    static final long PRESERVE_ZERO_FRACTION = 1024;
    static final long UNESCAPED_LINE_TERMINATORS = 2048;
    static final long THROW_ON_ERROR = 4194304;
    static final long OBJECT_AS_ARRAY = 1;
    static final long BIGINT_AS_STRING = 2;

    static final int ERROR_NONE = 0;
    static final int ERROR_DEPTH = 1;
    static final int ERROR_CTRL_CHAR = 3;
    static final int ERROR_SYNTAX = 4;
    static final int ERROR_INF_OR_NAN = 7;
    static final int ERROR_UTF16 = 10;

    private JsonFunctions() {
    }

    static void register() {
        Builtins.def("json_encode", 1, 3, JsonFunctions::encode);
        Builtins.def("json_decode", 1, 4, JsonFunctions::decode);
        Builtins.def("json_last_error", 0, 0, (ctx, a) -> (long) ctx.request().jsonLastError);
        Builtins.def("json_last_error_msg", 0, 0, (ctx, a) -> message(ctx.request().jsonLastError));
    }

    static String message(int code) {
        return switch (code) {
            case ERROR_NONE -> "No error";
            case ERROR_DEPTH -> "Maximum stack depth exceeded";
            case 2 -> "State mismatch (invalid or malformed JSON)";
            case ERROR_CTRL_CHAR -> "Control character error, possibly incorrectly encoded";
            case ERROR_SYNTAX -> "Syntax error";
            case 5 -> "Malformed UTF-8 characters, possibly incorrectly encoded";
            case 6 -> "Recursion detected";
            case ERROR_INF_OR_NAN -> "Inf and NaN cannot be JSON encoded";
            case 8 -> "Type is not supported";
            case 9 -> "The decoded property name is invalid";
            case ERROR_UTF16 -> "Single unpaired UTF-16 surrogate in unicode escape";
            default -> "Unknown error";
        };
    }

    // ------------------------------------------------------------------ encode

    private static Object encode(PhpContext ctx, Object[] a) {
        long flags = a.length > 1 ? PhpOps.toLong(a[1]) : 0;
        long depth = a.length > 2 ? PhpOps.toLong(a[2]) : 512;
        if (depth <= 0) {
            throw new PhpError("ValueError", "json_encode(): Argument #3 ($depth) must be greater than 0", null);
        }
        Encoder e = new Encoder(flags, depth);
        e.value(a[0], 0);
        if ((flags & THROW_ON_ERROR) != 0) {
            if (e.error != ERROR_NONE) {
                if ((flags & PARTIAL_OUTPUT_ON_ERROR) != 0) {
                    ctx.request().jsonLastError = e.error;
                    return e.sb.toString();
                }
                throw jsonException(ctx, "json_encode", e.error);
            }
            return e.sb.toString();
        }
        ctx.request().jsonLastError = e.error;
        if (e.error != ERROR_NONE && (flags & PARTIAL_OUTPUT_ON_ERROR) == 0) {
            return false;
        }
        return e.sb.toString();
    }

    private static final class Encoder {
        final StringBuilder sb = new StringBuilder();
        final long flags;
        final long maxDepth;
        int error = ERROR_NONE;

        Encoder(long flags, long maxDepth) {
            this.flags = flags;
            this.maxDepth = maxDepth;
        }

        boolean has(long flag) {
            return (flags & flag) != 0;
        }

        void value(Object v, int depth) {
            if (v == PhpNull.INSTANCE) {
                sb.append("null");
            } else if (v instanceof Boolean b) {
                sb.append(b);
            } else if (v instanceof Long l) {
                sb.append(l);
            } else if (v instanceof Double d) {
                number(d);
            } else if (v instanceof String s) {
                if (has(NUMERIC_CHECK) && PhpOps.isNumericString(s)) {
                    Object n = PhpOps.toNumber(s);
                    if (n instanceof Long l) {
                        sb.append(l);
                    } else {
                        number((Double) n);
                    }
                    return;
                }
                string(s);
            } else if (v instanceof PhpArray arr) {
                container(arr.entries(), has(FORCE_OBJECT) || !arr.isList(), depth);
            } else if (v instanceof dev.phpruntime.runtime.PhpObject obj) {
                // Public properties only; TODO: JsonSerializable once built-in interfaces exist.
                container(dev.phpruntime.runtime.PhpClass.visibleEntries(null, obj, null), true, depth);
            } else {
                error = 8;
                sb.append("null");
            }
        }

        void number(double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                error = ERROR_INF_OR_NAN;
                sb.append('0');
                return;
            }
            String f = PhpOps.formatDouble(d, 17).replace('E', 'e');
            sb.append(f);
            if (has(PRESERVE_ZERO_FRACTION) && f.indexOf('.') < 0 && f.indexOf('e') < 0) {
                sb.append(".0");
            }
        }

        void container(Iterable<Map.Entry<Object, Object>> entries, boolean object, int depth) {
            if (depth >= maxDepth) {
                error = ERROR_DEPTH;
                if (!has(PARTIAL_OUTPUT_ON_ERROR)) {
                    return;
                }
            }
            if (!entries.iterator().hasNext()) {
                sb.append(object ? "{}" : "[]");
                return;
            }
            boolean pretty = has(PRETTY_PRINT);
            sb.append(object ? '{' : '[');
            boolean first = true;
            for (Map.Entry<Object, Object> e : entries) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                if (pretty) {
                    sb.append('\n').append("    ".repeat(depth + 1));
                }
                if (object) {
                    string(PhpOps.toStr(e.getKey()));
                    sb.append(pretty ? ": " : ":");
                }
                value(e.getValue(), depth + 1);
            }
            if (pretty) {
                sb.append('\n').append("    ".repeat(depth));
            }
            sb.append(object ? '}' : ']');
        }

        void string(String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append(has(HEX_QUOT) ? "\\u0022" : "\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '/' -> sb.append(has(UNESCAPED_SLASHES) ? "/" : "\\/");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '<' -> sb.append(has(HEX_TAG) ? "\\u003C" : "<");
                    case '>' -> sb.append(has(HEX_TAG) ? "\\u003E" : ">");
                    case '&' -> sb.append(has(HEX_AMP) ? "\\u0026" : "&");
                    case '\'' -> sb.append(has(HEX_APOS) ? "\\u0027" : "'");
                    default -> {
                        boolean lineTerminator = c == ' ' || c == ' ';
                        if (c < 0x20
                                || (c >= 0x80 && !has(UNESCAPED_UNICODE))
                                || (lineTerminator && !has(UNESCAPED_LINE_TERMINATORS))) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }
    }

    // ------------------------------------------------------------------ decode

    private static Object decode(PhpContext ctx, Object[] a) {
        String json = PhpOps.toStr(a[0]);
        long flags = a.length > 3 ? PhpOps.toLong(a[3]) : 0;
        boolean assoc = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toBool(a[1]) : (flags & OBJECT_AS_ARRAY) != 0;
        long depth = a.length > 2 ? PhpOps.toLong(a[2]) : 512;
        if (depth <= 0) {
            throw new PhpError("ValueError", "json_decode(): Argument #3 ($depth) must be greater than 0", null);
        }
        // TODO(objects): without $assoc, JSON objects should decode to stdClass. Until objects
        // exist they decode to arrays either way.
        Decoder d = new Decoder(json, depth, (flags & BIGINT_AS_STRING) != 0);
        Object result;
        try {
            d.skipSpace();
            result = d.value(0);
            d.skipSpace();
            if (d.pos != json.length()) {
                throw new Fail(ERROR_SYNTAX);
            }
        } catch (Fail f) {
            if ((flags & THROW_ON_ERROR) != 0) {
                throw jsonException(ctx, "json_decode", f.code);
            }
            ctx.request().jsonLastError = f.code;
            return PhpNull.INSTANCE;
        }
        if ((flags & THROW_ON_ERROR) == 0) {
            ctx.request().jsonLastError = ERROR_NONE;
        }
        return result;
    }

    private static dev.phpruntime.runtime.PhpException jsonException(PhpContext ctx, String fn, int code) {
        com.oracle.truffle.api.nodes.Node site = ctx.currentBuiltinCallSite();
        String file = dev.phpruntime.runtime.PhpBacktrace.sourcePath(site);
        int line = dev.phpruntime.runtime.PhpBacktrace.sourceLine(site);
        dev.phpruntime.runtime.PhpBacktrace.LazyTrace trace =
                dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(site, fn);
        dev.phpruntime.runtime.PhpObject obj = dev.phpruntime.runtime.PhpExceptions.createBuiltinThrowable(
                ctx, "JsonException", message(code), code, file, line, trace);
        return new dev.phpruntime.runtime.PhpException(obj, site);
    }

    private static final class Fail extends RuntimeException {
        final int code;

        Fail(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    private static final class Decoder {
        final String s;
        final long maxDepth;
        final boolean bigintAsString;
        int pos;

        Decoder(String s, long maxDepth, boolean bigintAsString) {
            this.s = s;
            this.maxDepth = maxDepth;
            this.bigintAsString = bigintAsString;
        }

        void skipSpace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        Object value(int depth) {
            if (pos >= s.length()) {
                throw new Fail(ERROR_SYNTAX);
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", PhpNull.INSTANCE);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new Fail(ERROR_SYNTAX);
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw new Fail(ERROR_SYNTAX);
            }
            pos += word.length();
            return v;
        }

        PhpArray array(int depth) {
            if (depth >= maxDepth) {
                throw new Fail(ERROR_DEPTH);
            }
            pos++; // '['
            PhpArray arr = new PhpArray();
            skipSpace();
            if (peek() == ']') {
                pos++;
                return arr;
            }
            while (true) {
                skipSpace();
                arr.append(value(depth));
                skipSpace();
                char c = peek();
                pos++;
                if (c == ']') {
                    return arr;
                }
                if (c != ',') {
                    throw new Fail(ERROR_SYNTAX);
                }
            }
        }

        PhpArray object(int depth) {
            if (depth >= maxDepth) {
                throw new Fail(ERROR_DEPTH);
            }
            pos++; // '{'
            PhpArray arr = new PhpArray();
            skipSpace();
            if (peek() == '}') {
                pos++;
                return arr;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw new Fail(ERROR_SYNTAX);
                }
                String key = string();
                skipSpace();
                if (peek() != ':') {
                    throw new Fail(ERROR_SYNTAX);
                }
                pos++;
                skipSpace();
                arr.put(key, value(depth));
                skipSpace();
                char c = peek();
                pos++;
                if (c == '}') {
                    return arr;
                }
                if (c != ',') {
                    throw new Fail(ERROR_SYNTAX);
                }
            }
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : 0;
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new Fail(ERROR_SYNTAX);
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw new Fail(ERROR_CTRL_CHAR);
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new Fail(ERROR_SYNTAX);
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        char u = hex4();
                        if (Character.isHighSurrogate(u)) {
                            if (!s.startsWith("\\u", pos)) {
                                throw new Fail(ERROR_UTF16);
                            }
                            pos += 2;
                            char low = hex4();
                            if (!Character.isLowSurrogate(low)) {
                                throw new Fail(ERROR_UTF16);
                            }
                            sb.append(u).append(low);
                        } else if (Character.isLowSurrogate(u)) {
                            throw new Fail(ERROR_UTF16);
                        } else {
                            sb.append(u);
                        }
                    }
                    default -> throw new Fail(ERROR_SYNTAX);
                }
            }
        }

        char hex4() {
            if (pos + 4 > s.length()) {
                throw new Fail(ERROR_SYNTAX);
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos + i), 16);
                if (d < 0) {
                    throw new Fail(ERROR_SYNTAX);
                }
                v = v * 16 + d;
            }
            pos += 4;
            return (char) v;
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (peek() >= '1' && peek() <= '9') {
                while (Character.isDigit(peek())) {
                    pos++;
                }
            } else {
                throw new Fail(ERROR_SYNTAX);
            }
            boolean isDouble = false;
            if (peek() == '.') {
                pos++;
                if (!Character.isDigit(peek())) {
                    throw new Fail(ERROR_SYNTAX);
                }
                while (Character.isDigit(peek())) {
                    pos++;
                }
                isDouble = true;
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!Character.isDigit(peek())) {
                    throw new Fail(ERROR_SYNTAX);
                }
                while (Character.isDigit(peek())) {
                    pos++;
                }
                isDouble = true;
            }
            String text = s.substring(start, pos);
            if (!isDouble) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException overflow) {
                    if (bigintAsString) {
                        return text;
                    }
                }
            }
            return Double.parseDouble(text);
        }
    }
}
