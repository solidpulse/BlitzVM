package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * serialize and unserialize for scalars and arrays. String lengths are UTF-8 byte counts, as in
 * PHP, so data written by real PHP (WordPress options in the database) reads back correctly.
 */
final class SerializeFunctions {

    private SerializeFunctions() {
    }

    static void register() {
        Builtins.def("serialize", 1, 1, (ctx, a) -> {
            StringBuilder sb = new StringBuilder();
            serialize(sb, a[0]);
            return sb.toString();
        });
        Builtins.def("unserialize", 1, 2, (ctx, a) -> unserialize(ctx, PhpOps.toStr(a[0])));
    }

    static void serialize(StringBuilder sb, Object v) {
        if (v == PhpNull.INSTANCE || v == null) {
            sb.append("N;");
        } else if (v instanceof Boolean b) {
            sb.append("b:").append(b ? 1 : 0).append(';');
        } else if (v instanceof Long l) {
            sb.append("i:").append(l).append(';');
        } else if (v instanceof Double d) {
            sb.append("d:").append(PhpOps.formatDouble(d, 17)).append(';');
        } else if (v instanceof String s) {
            sb.append("s:").append(s.getBytes(StandardCharsets.UTF_8).length).append(":\"").append(s).append("\";");
        } else if (v instanceof PhpArray arr) {
            sb.append("a:").append(arr.size()).append(":{");
            for (Map.Entry<Object, Object> e : arr.entries()) {
                serialize(sb, e.getKey());
                serialize(sb, e.getValue());
            }
            sb.append('}');
        } else {
            // TODO(classes): O:<len>:"Class":<n>:{...}, __serialize/__sleep and Serializable.
            throw new PhpError("serialize(): serializing " + PhpOps.typeName(v) + " is not supported yet");
        }
    }

    /** Thrown internally for malformed input; becomes PHP's warning and false. */
    private static final class Malformed extends RuntimeException {
        final int offset;

        Malformed(int offset) {
            super(null, null, false, false);
            this.offset = offset;
        }
    }

    private static Object unserialize(PhpContext ctx, String str) {
        byte[] in = str.getBytes(StandardCharsets.UTF_8);
        int[] pos = {0};
        try {
            return value(in, pos);
        } catch (Malformed e) {
            ctx.warning("unserialize(): Error at offset " + e.offset + " of " + in.length + " bytes", 0);
            return false;
        }
    }

    private static Object value(byte[] in, int[] pos) {
        int start = pos[0];
        if (start + 1 >= in.length) {
            throw new Malformed(start);
        }
        char type = (char) in[start];
        if (type == 'N') {
            expect(in, start + 1, ';', start);
            pos[0] = start + 2;
            return PhpNull.INSTANCE;
        }
        expect(in, start + 1, ':', start);
        pos[0] = start + 2;
        switch (type) {
            case 'b' -> {
                String t = token(in, pos, ';', start);
                if (!t.equals("0") && !t.equals("1")) {
                    throw new Malformed(start);
                }
                return t.equals("1");
            }
            case 'i' -> {
                String t = token(in, pos, ';', start);
                try {
                    return Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
                } catch (NumberFormatException e) {
                    throw new Malformed(start);
                }
            }
            case 'd' -> {
                String t = token(in, pos, ';', start);
                return switch (t) {
                    case "INF" -> Double.POSITIVE_INFINITY;
                    case "-INF" -> Double.NEGATIVE_INFINITY;
                    case "NAN" -> Double.NaN;
                    default -> {
                        try {
                            yield Double.parseDouble(t);
                        } catch (NumberFormatException e) {
                            throw new Malformed(start);
                        }
                    }
                };
            }
            case 's' -> {
                int len = length(in, pos, start);
                expect(in, pos[0], '"', start);
                int from = pos[0] + 1;
                if (from + len + 1 >= in.length + 1 || from + len >= in.length) {
                    throw new Malformed(start);
                }
                expect(in, from + len, '"', start);
                expect(in, from + len + 1, ';', start);
                pos[0] = from + len + 2;
                return HashFunctions.fromBytes(Arrays.copyOfRange(in, from, from + len));
            }
            case 'a' -> {
                int n = length(in, pos, start);
                expect(in, pos[0], '{', start);
                pos[0]++;
                PhpArray arr = new PhpArray();
                for (int i = 0; i < n; i++) {
                    int keyAt = pos[0];
                    Object key = value(in, pos);
                    if (!(key instanceof Long) && !(key instanceof String)) {
                        throw new Malformed(keyAt);
                    }
                    arr.put(key, value(in, pos));
                }
                expect(in, pos[0], '}', pos[0]);
                pos[0]++;
                return arr;
            }
            default -> {
                // TODO(classes): O:, C:, E: and the r:/R: back-references.
                throw new Malformed(start);
            }
        }
    }

    private static void expect(byte[] in, int at, char c, int errorOffset) {
        if (at >= in.length || in[at] != c) {
            throw new Malformed(errorOffset);
        }
    }

    private static String token(byte[] in, int[] pos, char end, int errorOffset) {
        int i = pos[0];
        while (i < in.length && in[i] != end) {
            i++;
        }
        if (i >= in.length || i == pos[0]) {
            throw new Malformed(errorOffset);
        }
        String t = new String(in, pos[0], i - pos[0], StandardCharsets.US_ASCII);
        pos[0] = i + 1;
        return t;
    }

    /** The N in s:N: and a:N:, leaving pos after the second colon. */
    private static int length(byte[] in, int[] pos, int errorOffset) {
        String t = token(in, pos, ':', errorOffset);
        try {
            int n = Integer.parseInt(t);
            if (n < 0) {
                throw new Malformed(errorOffset);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new Malformed(errorOffset);
        }
    }
}
