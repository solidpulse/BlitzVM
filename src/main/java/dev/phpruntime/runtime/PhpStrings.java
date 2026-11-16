package dev.phpruntime.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * The only boundary between PHP byte strings and Java text. Each char of a PHP string
 * is an unsigned byte, so length, indexing and comparison have PHP's byte semantics.
 */
public final class PhpStrings {
    public static final boolean CHECK = Boolean.getBoolean("blitz.checkBytes");

    private PhpStrings() {
    }

    public static byte[] bytes(String php) {
        return php.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static String of(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    public static String of(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }

    public static String fromJava(String text) {
        return isAscii(text) ? text : of(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String toJava(String php) {
        return isAscii(php) ? php : new String(bytes(php), StandardCharsets.UTF_8);
    }

    public static String codePoint(int cp) {
        return fromJava(new String(Character.toChars(cp)));
    }

    public static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 128) {
                return false;
            }
        }
        return true;
    }

    public static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    public static char upper(char c) {
        return c >= 'a' && c <= 'z' ? (char) (c - 32) : c;
    }

    public static String lower(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 'A' && s.charAt(i) <= 'Z') {
                char[] chars = s.toCharArray();
                for (int j = i; j < chars.length; j++) {
                    chars[j] = lower(chars[j]);
                }
                return new String(chars);
            }
        }
        return s;
    }

    public static String upper(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 'a' && s.charAt(i) <= 'z') {
                char[] chars = s.toCharArray();
                for (int j = i; j < chars.length; j++) {
                    chars[j] = upper(chars[j]);
                }
                return new String(chars);
            }
        }
        return s;
    }

    /** The valid UTF-8 sequence's byte count, or zero for an invalid lead/sequence. */
    public static int utf8SequenceLength(String s, int i) {
        int c = s.charAt(i);
        if (c < 128) {
            return 1;
        }
        int n = s.length();
        if (c >= 0xC2 && c <= 0xDF && i + 1 < n && continuation(s.charAt(i + 1))) {
            return 2;
        }
        if (c >= 0xE0 && c <= 0xEF && i + 2 < n) {
            int b = s.charAt(i + 1);
            if (continuation(b) && continuation(s.charAt(i + 2))
                    && (c != 0xE0 || b >= 0xA0) && (c != 0xED || b < 0xA0)) {
                return 3;
            }
        }
        if (c >= 0xF0 && c <= 0xF4 && i + 3 < n) {
            int b = s.charAt(i + 1);
            if (continuation(b) && continuation(s.charAt(i + 2)) && continuation(s.charAt(i + 3))
                    && (c != 0xF0 || b >= 0x90) && (c != 0xF4 || b < 0x90)) {
                return 4;
            }
        }
        return 0;
    }

    private static boolean continuation(int b) {
        return b >= 0x80 && b <= 0xBF;
    }

    public static int utf8CodePoint(String s, int i, int length) {
        int cp = s.charAt(i) & (length == 1 ? 0x7F : length == 2 ? 0x1F : length == 3 ? 0x0F : 7);
        for (int j = 1; j < length; j++) {
            cp = (cp << 6) | (s.charAt(i + j) & 63);
        }
        return cp;
    }

    public static boolean isUtf8(String s) {
        for (int i = 0; i < s.length();) {
            int width = utf8SequenceLength(s, i);
            if (width == 0) {
                return false;
            }
            i += width;
        }
        return true;
    }

    /** mbstring counts each invalid byte as a character too. */
    public static int utf8Length(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); count++) {
            i += Math.max(1, utf8SequenceLength(s, i));
        }
        return count;
    }

    /** Decode with an explicit substitute for invalid bytes (mbstring's default is "?"). */
    public static String decodeUtf8(String s, String substitute) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length();) {
            int width = utf8SequenceLength(s, i);
            if (width == 0) {
                out.append(substitute);
                i++;
            } else {
                out.appendCodePoint(utf8CodePoint(s, i, width));
                i += width;
            }
        }
        return out.toString();
    }

    /** Charset boundaries used by mbstring, iconv and HTML, never by byte-level functions. */
    public static String decode(String php, Charset charset, String substitute) {
        if (charset.equals(StandardCharsets.UTF_8)) {
            return decodeUtf8(php, substitute);
        }
        var decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(substitute.isEmpty() ? "\uFFFD" : substitute);
        try {
            String text = decoder.decode(ByteBuffer.wrap(bytes(php))).toString();
            return substitute.isEmpty() ? text.replace("\uFFFD", "") : text;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Replacing charset decoder failed", e);
        }
    }

    public static String encode(String text, Charset charset, String substitute) {
        if (charset.equals(StandardCharsets.UTF_8)) {
            return fromJava(text);
        }
        var encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            try {
                ByteBuffer b = encoder.encode(CharBuffer.wrap(ch));
                byte[] raw = new byte[b.remaining()];
                b.get(raw);
                out.append(of(raw));
            } catch (CharacterCodingException e) {
                if (!substitute.isEmpty()) {
                    out.append(of(substitute.getBytes(charset)));
                }
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    public static boolean isEncoding(String php, Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8)) {
            return isUtf8(php);
        }
        try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes(php)));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** One linear pass per Unicode regex call; subsequent lookups are O(1)/O(log n). */
    public static final class Utf8Offsets {
        private final int[] byteOffsets;
        private final int length;

        public Utf8Offsets(String text) {
            length = text.length();
            if (isAscii(text)) {
                byteOffsets = null;
                return;
            }
            byteOffsets = new int[length + 1];
            int bytes = 0;
            for (int i = 0; i < length;) {
                int cp = text.codePointAt(i);
                byteOffsets[i] = bytes;
                int chars = Character.charCount(cp);
                if (chars == 2) {
                    byteOffsets[i + 1] = bytes;
                }
                bytes += cp < 128 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                i += chars;
                byteOffsets[i] = bytes;
            }
        }

        public int toBytes(int charIndex) {
            return byteOffsets == null ? charIndex : byteOffsets[charIndex];
        }

        /** -1 means the requested byte lies inside a multi-byte character. */
        public int toChars(int byteOffset) {
            if (byteOffsets == null) {
                return byteOffset >= 0 && byteOffset <= length ? byteOffset : -1;
            }
            int index = Arrays.binarySearch(byteOffsets, byteOffset);
            if (index > 0 && byteOffsets[index - 1] == byteOffset) {
                index--;
            }
            return index < 0 ? -1 : index;
        }
    }

    public static void check(String s, String origin) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 255) {
                throw new IllegalStateException("Non-byte PHP string from " + origin + " at index " + i
                        + " (U+" + Integer.toHexString(s.charAt(i)) + ")");
            }
        }
    }

    public static void checkValue(Object value, String origin) {
        checkValue(value, origin, new IdentityHashMap<>());
    }

    private static void checkValue(Object value, String origin, IdentityHashMap<Object, Boolean> seen) {
        if (value instanceof String s) {
            check(s, origin);
        } else if (value instanceof PhpReference ref) {
            if (seen.put(ref, true) == null) {
                checkValue(ref.value, origin, seen);
            }
        } else if (value instanceof PhpArray arr && seen.put(arr, true) == null) {
            for (var e : arr.entries()) {
                checkValue(e.getKey(), origin, seen);
                checkValue(e.getValue(), origin, seen);
            }
        } else if (value instanceof PhpObject obj && seen.put(obj, true) == null) {
            for (var e : PhpClass.visibleEntries(null, obj, null)) {
                checkValue(e.getKey(), origin, seen);
                checkValue(e.getValue(), origin, seen);
            }
        }
    }
}
