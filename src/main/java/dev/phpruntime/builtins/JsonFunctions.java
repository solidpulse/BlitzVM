package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.util.ArrayList;
import java.util.List;
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
    static final long INVALID_UTF8_IGNORE = 1048576;
    static final long INVALID_UTF8_SUBSTITUTE = 2097152;
    static final long THROW_ON_ERROR = 4194304;
    static final long OBJECT_AS_ARRAY = 1;
    static final long BIGINT_AS_STRING = 2;

    static final int ERROR_NONE = 0;
    static final int ERROR_DEPTH = 1;
    static final int ERROR_CTRL_CHAR = 3;
    static final int ERROR_SYNTAX = 4;
    static final int ERROR_UTF8 = 5;
    static final int ERROR_INF_OR_NAN = 7;
    static final int ERROR_UTF16 = 10;

    private JsonFunctions() {
    }

    static void register() {
        Builtins.def("json_encode", 1, 3, JsonFunctions::encode);
        Builtins.def("json_decode", 1, 4, JsonFunctions::decode);
        Builtins.def("json_validate", 1, 3, JsonFunctions::validate);
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

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static Object encode(PhpContext ctx, Object[] a) {
        long flags = a.length > 1 ? PhpOps.toLong(a[1]) : 0;
        long depth = a.length > 2 ? PhpOps.toLong(a[2]) : 512;
        if (depth <= 0) {
            throw new PhpError("ValueError", "json_encode(): Argument #3 ($depth) must be greater than 0", null);
        }
        Encoder e = new Encoder(ctx, flags, depth,
                a[0] instanceof PhpArray top ? (int) Math.min(1 << 20, 16L + top.size() * 8L) : 64);
        e.value(a[0], 0);
        if ((flags & THROW_ON_ERROR) != 0) {
            if (e.error != ERROR_NONE) {
                if ((flags & PARTIAL_OUTPUT_ON_ERROR) != 0) {
                    ctx.request().jsonLastError = e.error;
                    return PhpStrings.fromJava(e.sb.toString());
                }
                throw jsonException(ctx, "json_encode", e.error);
            }
            return PhpStrings.fromJava(e.sb.toString());
        }
        ctx.request().jsonLastError = e.error;
        if (e.error != ERROR_NONE && (flags & PARTIAL_OUTPUT_ON_ERROR) == 0) {
            return false;
        }
        return PhpStrings.fromJava(e.sb.toString());
    }

    private static final class Encoder {
        final StringBuilder sb;
        final long flags;
        final long maxDepth;
        final PhpContext ctx;
        /** Objects being encoded at this point in the recursion, outermost first. */
        private Object[] ancestors;
        private int ancestorCount;
        /** Only allocated when an object implements JsonSerializable. */
        private java.util.IdentityHashMap<Object, Boolean> serializing;
        int error = ERROR_NONE;

        Encoder(PhpContext ctx, long flags, long maxDepth, int initialCapacity) {
            this.ctx = ctx;
            this.flags = flags;
            this.maxDepth = maxDepth;
            this.sb = new StringBuilder(Math.max(16, initialCapacity));
        }

        boolean has(long flag) {
            return (flags & flag) != 0;
        }

        private boolean isAncestor(Object value) {
            for (int i = 0; i < ancestorCount; i++) {
                if (ancestors[i] == value) {
                    return true;
                }
            }
            return false;
        }

        private void enter(Object value) {
            if (ancestors == null) {
                ancestors = new Object[8];
            } else if (ancestorCount == ancestors.length) {
                ancestors = java.util.Arrays.copyOf(ancestors, ancestorCount * 2);
            }
            ancestors[ancestorCount++] = value;
        }

        private void leave() {
            ancestors[--ancestorCount] = null;
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
                if (isAncestor(arr)) {
                    error = 6;
                    sb.append("null");
                    return;
                }
                enter(arr);
                container(arr, depth);
                leave();
            } else if (v instanceof dev.phpruntime.runtime.PhpObject obj) {
                if (isAncestor(obj)) {
                    error = 6;
                    sb.append("null");
                    return;
                }
                if (obj.phpClass().hasSplInterface(dev.phpruntime.runtime.SplInterfaces.JSON_SERIALIZABLE)
                        && !isSerializing(obj)) {
                    startSerializing(obj);
                    Object result = dev.phpruntime.runtime.SplIterators.call(obj, "jsonSerialize");
                    if (result == obj) {
                        value(obj, depth);
                    } else {
                        enter(obj);
                        value(result, depth);
                        leave();
                    }
                    stopSerializing(obj);
                    return;
                }
                enter(obj);
                if (obj.nativeState() instanceof dev.phpruntime.runtime.SplArrayState state) {
                    container((state.flags & dev.phpruntime.runtime.SplArrayState.STD_PROP_LIST) == 0
                            ? state.arrayCopy().entries() : dev.phpruntime.runtime.PhpClass.visibleEntries(ctx, obj, null), true, depth);
                    leave();
                    return;
                }
                List<Map.Entry<Object, Object>> entries = new ArrayList<>(
                        dev.phpruntime.runtime.PhpClass.visibleEntries(null, obj, null));
                if (obj.nativeState() != null) {
                    Map<String, Object> extra = obj.nativeState().debugProperties(obj);
                    if (extra != null) {
                        for (Map.Entry<String, Object> e : extra.entrySet()) {
                            entries.add(Map.entry(e.getKey(), e.getValue()));
                        }
                    }
                }
                container(entries, true, depth);
                leave();
            } else {
                error = 8;
                sb.append("null");
            }
        }

        private boolean isSerializing(Object obj) {
            return serializing != null && serializing.containsKey(obj);
        }

        private void startSerializing(Object obj) {
            if (serializing == null) {
                serializing = new java.util.IdentityHashMap<>();
            }
            serializing.put(obj, true);
        }

        private void stopSerializing(Object obj) {
            serializing.remove(obj);
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

        void container(PhpArray arr, int depth) {
            boolean list = arr.isList();
            if (!has(FORCE_OBJECT) && list) {
                Object[] values = arr.packedValues();
                if (values != null) {
                    values(values, depth);
                    return;
                }
            }
            container(arr.entries(), has(FORCE_OBJECT) || !list, depth);
        }

        void values(Object[] values, int depth) {
            if (depth >= maxDepth) {
                error = ERROR_DEPTH;
                if (!has(PARTIAL_OUTPUT_ON_ERROR)) {
                    return;
                }
            }
            if (values.length == 0) {
                sb.append("[]");
                return;
            }
            boolean pretty = has(PRETTY_PRINT);
            sb.append('[');
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                if (pretty) {
                    sb.append('\n').append("    ".repeat(depth + 1));
                }
                value(values[i], depth + 1);
            }
            if (pretty) {
                sb.append('\n').append("    ".repeat(depth));
            }
            sb.append(']');
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
                    String key = PhpOps.toStr(e.getKey());
                    if (!PhpStrings.isUtf8(key) && !has(INVALID_UTF8_IGNORE) && !has(INVALID_UTF8_SUBSTITUTE)) {
                        error = ERROR_UTF8;
                        sb.append("\"\"");
                    } else {
                        string(key);
                    }
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
            String text;
            if (PhpStrings.isAscii(s)) {
                text = s;
            } else {
                text = PhpStrings.toJavaUtf8OrNull(s);
                if (text == null) {
                    if (!has(INVALID_UTF8_SUBSTITUTE) && !has(INVALID_UTF8_IGNORE)) {
                        error = ERROR_UTF8;
                        sb.append("null");
                        return;
                    }
                    text = PhpStrings.decodeUtf8Replacing(s, has(INVALID_UTF8_IGNORE));
                }
            }
            if (nothingToEscape(text)) {
                sb.append('"').append(text).append('"');
                return;
            }
            sb.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
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
                        boolean lineTerminator = c == 0x2028 || c == 0x2029;
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

        /** True when appending the Java text unchanged is exactly what the escaping loop would do. */
        private boolean nothingToEscape(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20 || c == '"' || c == '\\' || c == '/' || c == '<' || c == '>'
                        || c == '&' || c == '\'') {
                    return false;
                }
                if (c >= 0x80 && (!has(UNESCAPED_UNICODE)
                        || ((c == 0x2028 || c == 0x2029) && !has(UNESCAPED_LINE_TERMINATORS)))) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ decode

    private static Object decode(PhpContext ctx, Object[] a) {
        String json = PhpOps.toStr(a[0]);
        long flags = a.length > 3 ? PhpOps.toLong(a[3]) : 0;
        if (!PhpStrings.isUtf8(json)) {
            if ((flags & (INVALID_UTF8_IGNORE | INVALID_UTF8_SUBSTITUTE)) == 0) {
                if ((flags & THROW_ON_ERROR) != 0) {
                    throw jsonException(ctx, "json_decode", ERROR_UTF8);
                }
                ctx.request().jsonLastError = ERROR_UTF8;
                return PhpNull.INSTANCE;
            }
            json = PhpStrings.decodeUtf8Json(json, (flags & INVALID_UTF8_IGNORE) != 0);
        } else {
            json = PhpStrings.toJava(json);
        }
        boolean assoc = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toBool(a[1]) : (flags & OBJECT_AS_ARRAY) != 0;
        long depth = a.length > 2 ? PhpOps.toLong(a[2]) : 512;
        if (depth <= 0) {
            throw new PhpError("ValueError", "json_decode(): Argument #3 ($depth) must be greater than 0", null);
        }
        // Without $assoc (or JSON_OBJECT_AS_ARRAY), JSON objects decode to stdClass.
        Decoder d = new Decoder(json, depth, (flags & BIGINT_AS_STRING) != 0, assoc ? null : ctx);
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

    private static Object validate(PhpContext ctx, Object[] a) {
        String json = PhpOps.toStr(a[0]);
        long depth = a.length > 1 ? PhpOps.toLong(a[1]) : 512;
        long flags = a.length > 2 ? PhpOps.toLong(a[2]) : 0;
        if (depth <= 0) {
            throw new PhpError("ValueError", "json_validate(): Argument #2 ($depth) must be greater than 0", null);
        }
        if ((flags & ~INVALID_UTF8_IGNORE) != 0) {
            throw new PhpError("ValueError", "json_validate(): Argument #3 ($flags) must be a valid flag"
                    + " (allowed flags: JSON_INVALID_UTF8_IGNORE)", null);
        }
        if (!PhpStrings.isUtf8(json)) {
            if ((flags & INVALID_UTF8_IGNORE) == 0) {
                return false;
            }
            json = PhpStrings.decodeUtf8Json(json, true);
        } else {
            json = PhpStrings.toJava(json);
        }
        Decoder d = new Decoder(json, depth, false, null);
        try {
            d.skipSpace();
            d.value(0);
            d.skipSpace();
            return d.pos == json.length();
        } catch (Fail f) {
            return false;
        }
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
        /** Non-null when JSON objects become stdClass instances rather than arrays. */
        final dev.phpruntime.PhpContext objects;
        int pos;

        Decoder(String s, long maxDepth, boolean bigintAsString, dev.phpruntime.PhpContext objects) {
            this.s = s;
            this.maxDepth = maxDepth;
            this.bigintAsString = bigintAsString;
            this.objects = objects;
        }

        Object finish(PhpArray arr) {
            return objects == null ? arr : dev.phpruntime.nodes.expr.CastNode.toObject(objects, arr);
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

        Object object(int depth) {
            if (depth >= maxDepth) {
                throw new Fail(ERROR_DEPTH);
            }
            pos++; // '{'
            // JSON object members always have string keys; start in the hash layout so the first
            // member does not convert the array.
            PhpArray arr = PhpArray.withCapacity(8, true);
            skipSpace();
            if (peek() == '}') {
                pos++;
                return finish(arr);
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
                    return finish(arr);
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
                    return PhpStrings.fromJava(sb.toString());
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
