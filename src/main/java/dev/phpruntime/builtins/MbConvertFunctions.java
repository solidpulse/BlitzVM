package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * mb_convert_encoding, mb_scrub and mb_detect_encoding for UTF-8, ASCII, ISO-8859-1,
 * Windows-1252 and the UTF-16/32 variants. Strings are Java strings until byte strings land,
 * so input in a single-byte encoding is taken as raw bytes (see {@link BinaryStrings}).
 */
final class MbConvertFunctions {

    private MbConvertFunctions() {
    }

    /** A supported encoding: the name mbstring reports and the Java charset behind it. */
    record Encoding(String name, Charset charset) {
        boolean singleByte() {
            return charset.equals(StandardCharsets.ISO_8859_1) || charset.name().equals("windows-1252")
                    || charset.equals(StandardCharsets.US_ASCII);
        }
    }

    static Encoding encoding(String name) {
        String n = name.trim().toUpperCase(Locale.ROOT).replace('_', '-');
        return switch (n) {
            case "UTF-8", "UTF8" -> new Encoding("UTF-8", StandardCharsets.UTF_8);
            case "ASCII", "US-ASCII" -> new Encoding("ASCII", StandardCharsets.US_ASCII);
            case "ISO-8859-1", "ISO8859-1", "LATIN1", "LATIN-1" -> new Encoding("ISO-8859-1", StandardCharsets.ISO_8859_1);
            case "WINDOWS-1252", "CP1252" -> new Encoding("Windows-1252", Charset.forName("windows-1252"));
            case "UTF-16", "UTF-16BE" -> new Encoding(n, StandardCharsets.UTF_16BE);
            case "UTF-16LE" -> new Encoding("UTF-16LE", StandardCharsets.UTF_16LE);
            case "UTF-32", "UTF-32BE" -> new Encoding(n, Charset.forName("UTF-32BE"));
            case "UTF-32LE" -> new Encoding("UTF-32LE", Charset.forName("UTF-32LE"));
            default -> null;
        };
    }

    static void register() {
        Builtins.def("mb_convert_encoding", 2, 3, (ctx, a) -> {
            Encoding to = encoding(PhpOps.toStr(a[1]));
            if (to == null) {
                throw new PhpError("ValueError", "mb_convert_encoding(): Argument #2 ($to_encoding) must be a valid encoding, \""
                        + PhpOps.toStr(a[1]) + "\" given", null);
            }
            List<Encoding> from = encodings(a.length > 2 ? a[2] : PhpNull.INSTANCE, "mb_convert_encoding", 3);
            return convertValue(a[0], to, from);
        });
        Builtins.def("mb_scrub", 1, 2, (ctx, a) -> {
            // Java strings cannot hold invalid UTF-8; only lone surrogates are left to replace.
            String s = PhpOps.toStr(a[0]);
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(++i));
                } else if (Character.isSurrogate(c)) {
                    sb.append('?');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        });
        Builtins.def("mb_detect_encoding", 1, 3, (ctx, a) -> {
            String s = PhpOps.toStr(a[0]);
            List<Encoding> candidates = encodings(a.length > 1 ? a[1] : PhpNull.INSTANCE, "mb_detect_encoding", 2);
            Encoding found = detect(s, candidates);
            return found == null ? false : found.name();
        });
    }

    private static Object convertValue(Object v, Encoding to, List<Encoding> from) {
        if (v instanceof PhpArray arr) {
            PhpArray out = new PhpArray();
            for (var e : arr.entries()) {
                Object key = e.getKey() instanceof String k ? convert(k, to, from) : e.getKey();
                out.put(key, convertValue(e.getValue(), to, from));
            }
            return out;
        }
        return convert(PhpOps.toStr(v), to, from);
    }

    private static String convert(String s, Encoding to, List<Encoding> from) {
        Encoding source = from.size() == 1 ? from.get(0) : detect(s, from);
        if (source == null) {
            source = from.get(0);
        }
        byte[] in = source.charset().equals(StandardCharsets.UTF_8) ? s.getBytes(StandardCharsets.UTF_8) : BinaryStrings.bytes(s);
        String text = decode(in, source.charset());
        byte[] out = encode(text, to.charset());
        return HashFunctions.fromBytes(out);
    }

    /** Decodes, replacing each invalid sequence with '?', as mbstring's default substitute does. */
    private static String decode(byte[] in, Charset cs) {
        CharsetDecoder d = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith("?");
        try {
            return d.decode(ByteBuffer.wrap(in)).toString();
        } catch (CharacterCodingException e) {
            return new String(in, cs);
        }
    }

    private static byte[] encode(String text, Charset cs) {
        CharsetEncoder e = cs.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(new byte[]{'?'});
        try {
            ByteBuffer bb = e.encode(CharBuffer.wrap(text));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        } catch (CharacterCodingException ex) {
            return text.getBytes(cs);
        }
    }

    /** First candidate the string is valid in: ASCII for pure ASCII, UTF-8 for valid Unicode text, else single-byte. */
    private static Encoding detect(String s, List<Encoding> candidates) {
        boolean ascii = s.chars().allMatch(c -> c < 0x80);
        for (Encoding e : candidates) {
            if (e.charset().equals(StandardCharsets.US_ASCII)) {
                if (ascii) {
                    return e;
                }
            } else if (e.charset().equals(StandardCharsets.UTF_8)) {
                if (!hasLoneSurrogate(s)) {
                    return e;
                }
            } else if (e.singleByte()) {
                return e;
            }
        }
        return null;
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Encoding list argument: null (detect order), a comma-separated string or an array. */
    private static List<Encoding> encodings(Object v, String fn, int argNo) {
        List<String> names = new ArrayList<>();
        if (v == PhpNull.INSTANCE) {
            names.add("ASCII");
            names.add("UTF-8");
        } else if (v instanceof PhpArray arr) {
            for (Object x : arr.values()) {
                names.add(PhpOps.toStr(x));
            }
        } else {
            String s = PhpOps.toStr(v);
            if (s.isEmpty() || s.equalsIgnoreCase("auto")) {
                names.add("ASCII");
                names.add("UTF-8");
            } else {
                for (String part : s.split(",")) {
                    names.add(part.trim());
                }
            }
        }
        List<Encoding> out = new ArrayList<>();
        for (String n : names) {
            Encoding e = encoding(n);
            if (e == null) {
                throw new PhpError("ValueError", fn + "(): Argument #" + argNo + " ($" + (argNo == 3 ? "from_encoding" : "encodings")
                        + ") contains invalid encoding \"" + n + "\"", null);
            }
            out.add(e);
        }
        return out;
    }
}
