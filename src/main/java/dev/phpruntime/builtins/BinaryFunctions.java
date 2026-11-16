package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** pack/unpack and MIME quoted-printable, with no text decoding. */
final class BinaryFunctions {
    private BinaryFunctions() {
    }

    static void register() {
        Builtins.def("pack", 1, -1, BinaryFunctions::pack);
        Builtins.def("unpack", 2, 3, BinaryFunctions::unpack);
        Builtins.def("quoted_printable_encode", 1, 1, (ctx, a) -> quotedEncode(PhpOps.toStr(a[0])));
        Builtins.def("quoted_printable_decode", 1, 1, (ctx, a) -> quotedDecode(PhpOps.toStr(a[0])));
    }

    private static int width(char code) {
        return switch (code) {
            case 'c', 'C' -> 1;
            case 's', 'S', 'n', 'v' -> 2;
            case 'i', 'I', 'l', 'L', 'N', 'V', 'f', 'g', 'G' -> 4;
            case 'q', 'Q', 'J', 'P', 'd', 'e', 'E' -> 8;
            default -> 0;
        };
    }

    private static ByteOrder order(char code) {
        return switch (code) {
            case 'n', 'N', 'J', 'G', 'E' -> ByteOrder.BIG_ENDIAN;
            case 'v', 'V', 'P', 'g', 'e' -> ByteOrder.LITTLE_ENDIAN;
            default -> ByteOrder.nativeOrder();
        };
    }

    private record Count(int count, boolean star, int next) {
    }

    private static Count count(String format, int i) {
        if (i < format.length() && format.charAt(i) == '*') {
            return new Count(0, true, i + 1);
        }
        int start = i;
        int n = 0;
        while (i < format.length() && format.charAt(i) >= '0' && format.charAt(i) <= '9') {
            n = Math.addExact(Math.multiplyExact(n, 10), format.charAt(i++) - '0');
        }
        return new Count(i == start ? 1 : n, false, i);
    }

    private static Object pack(PhpContext ctx, Object[] args) {
        String format = PhpOps.toStr(args[0]);
        StringBuilder out = new StringBuilder();
        int arg = 1;
        for (int i = 0; i < format.length();) {
            char code = format.charAt(i++);
            Count c = count(format, i);
            i = c.next;
            int n = c.count;
            if (code == 'x' || code == 'X' || code == '@') {
                if (code == 'x') {
                    out.append("\0".repeat(n));
                } else if (code == 'X') {
                    out.setLength(Math.max(0, out.length() - n));
                } else {
                    if (n > out.length()) {
                        out.append("\0".repeat(n - out.length()));
                    } else {
                        out.setLength(n);
                    }
                }
                continue;
            }
            if (arg >= args.length) {
                throw new PhpError(PhpError.VALUE_ERROR, "pack(): Type " + code + ": not enough arguments", null);
            }
            if ("aAZhH".indexOf(code) >= 0) {
                String s = PhpOps.toStr(args[arg++]);
                n = c.star ? s.length() + (code == 'Z' ? 1 : 0) : n;
                if (code == 'h' || code == 'H') {
                    for (int k = 0; k < n; k += 2) {
                        int a = k < s.length() ? Character.digit(s.charAt(k), 16) : 0;
                        int b = k + 1 < n && k + 1 < s.length() ? Character.digit(s.charAt(k + 1), 16) : 0;
                        out.append((char) (code == 'H' ? (Math.max(0, a) << 4) | Math.max(0, b)
                                : Math.max(0, a) | (Math.max(0, b) << 4)));
                    }
                } else {
                    int take = Math.min(s.length(), code == 'Z' ? Math.max(0, n - 1) : n);
                    out.append(s, 0, take).append(String.valueOf(code == 'A' ? ' ' : '\0').repeat(n - take));
                }
                continue;
            }
            int w = width(code);
            if (w == 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "pack(): Type " + code + ": unknown format code", null);
            }
            n = c.star ? args.length - arg : n;
            for (int k = 0; k < n; k++) {
                if (arg >= args.length) {
                    throw new PhpError(PhpError.VALUE_ERROR, "pack(): Type " + code + ": not enough arguments", null);
                }
                ByteBuffer b = ByteBuffer.allocate(w).order(order(code));
                Object v = args[arg++];
                if ("fgG".indexOf(code) >= 0) {
                    b.putFloat((float) PhpOps.toDouble(v));
                } else if ("deE".indexOf(code) >= 0) {
                    b.putDouble(PhpOps.toDouble(v));
                } else {
                    long value = PhpOps.toLong(v);
                    switch (w) {
                        case 1 -> b.put((byte) value);
                        case 2 -> b.putShort((short) value);
                        case 4 -> b.putInt((int) value);
                        default -> b.putLong(value);
                    }
                }
                out.append(PhpStrings.of(b.array()));
            }
        }
        return out.toString();
    }

    private static Object unpack(PhpContext ctx, Object[] args) {
        String format = PhpOps.toStr(args[0]);
        String data = PhpOps.toStr(args[1]);
        int pos = args.length > 2 ? (int) PhpOps.toLong(args[2]) : 0;
        if (pos < 0 || pos > data.length()) {
            throw new PhpError(PhpError.VALUE_ERROR, "unpack(): Argument #3 ($offset) must be contained in argument #2 ($string)", null);
        }
        int base = pos;
        PhpArray out = new PhpArray();
        for (String field : format.split("/")) {
            if (field.isEmpty()) {
                continue;
            }
            char code = field.charAt(0);
            Count c = count(field, 1);
            int n = c.count;
            String name = field.substring(c.next);
            if ("xX@".indexOf(code) >= 0) {
                pos = code == 'x' ? pos + (c.star ? data.length() - pos : n)
                        : code == 'X' ? Math.max(base, pos - n) : base + n;
                if (pos > data.length()) {
                    return false;
                }
                continue;
            }
            if ("aAZhH".indexOf(code) >= 0) {
                n = c.star ? (data.length() - pos) * (code == 'h' || code == 'H' ? 2 : 1) : n;
                int byteCount = code == 'h' || code == 'H' ? (n + 1) / 2 : n;
                if (pos + byteCount > data.length()) {
                    return false;
                }
                String value = data.substring(pos, pos + byteCount);
                pos += byteCount;
                if (code == 'A') {
                    value = value.replaceAll("[ \\x00]+$", "");
                } else if (code == 'Z') {
                    int nul = value.indexOf('\0');
                    if (nul >= 0) {
                        value = value.substring(0, nul);
                    }
                } else if (code == 'h' || code == 'H') {
                    StringBuilder hex = new StringBuilder();
                    for (int k = 0; k < value.length(); k++) {
                        int b = value.charAt(k);
                        hex.append(Character.forDigit(code == 'H' ? b >> 4 : b & 15, 16));
                        hex.append(Character.forDigit(code == 'H' ? b & 15 : b >> 4, 16));
                    }
                    value = hex.substring(0, n);
                }
                out.put(name.isEmpty() ? 1L : name, value);
                continue;
            }
            int w = width(code);
            if (w == 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "unpack(): Type " + code + ": unknown format code", null);
            }
            n = c.star ? (data.length() - pos) / w : n;
            for (int k = 0; k < n; k++) {
                if (pos + w > data.length()) {
                    return false;
                }
                ByteBuffer b = ByteBuffer.wrap(PhpStrings.bytes(data.substring(pos, pos + w))).order(order(code));
                pos += w;
                Object value;
                if ("fgG".indexOf(code) >= 0) {
                    value = (double) b.getFloat();
                } else if ("deE".indexOf(code) >= 0) {
                    value = b.getDouble();
                } else {
                    long v = switch (w) {
                        case 1 -> b.get();
                        case 2 -> b.getShort();
                        case 4 -> b.getInt();
                        default -> b.getLong();
                    };
                    if ("CSnvILNV".indexOf(code) >= 0 && w < 8) {
                        v &= (1L << (w * 8)) - 1;
                    }
                    value = v;
                }
                Object key = name.isEmpty() ? (Object) (long) (k + 1) : name + (n == 1 && !c.star ? "" : k + 1);
                out.put(key, value);
            }
        }
        return out;
    }

    private static String quotedDecode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 1 < s.length()) {
                int j = i + 1;
                while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == '\n' || s.charAt(j) == '\r')) {
                    i = j + (s.charAt(j) == '\r' && j + 1 < s.length() && s.charAt(j + 1) == '\n' ? 1 : 0);
                    continue;
                }
                if (i + 2 < s.length()) {
                    int a = Character.digit(s.charAt(i + 1), 16);
                    int b = Character.digit(s.charAt(i + 2), 16);
                    if (a >= 0 && b >= 0) {
                        out.append((char) ((a << 4) | b));
                        i += 2;
                        continue;
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String quotedEncode(String s) {
        StringBuilder out = new StringBuilder();
        int column = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                out.append("\r\n");
                column = 0;
                i++;
                continue;
            }
            boolean trailing = c == ' ' && (i + 1 == s.length() || s.charAt(i + 1) == '\r' || s.charAt(i + 1) == '\n');
            String piece = c >= 32 && c <= 126 && c != '=' && !trailing ? String.valueOf((char) c)
                    : "=" + "0123456789ABCDEF".charAt(c >> 4) + "0123456789ABCDEF".charAt(c & 15);
            if (column + piece.length() > 75) {
                out.append("=\r\n");
                column = 0;
            }
            out.append(piece);
            column += piece.length();
        }
        return out.toString();
    }
}
