package dev.phpruntime.builtins;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Bytes back out of a PHP string, for functions whose argument is usually binary (compressed
 * data, raw hashes, packed addresses). {@link HashFunctions#fromBytes} turns bytes that are not
 * valid UTF-8 into one char per byte; this undoes that. A string of chars up to 0xFF whose
 * Latin-1 bytes are not valid UTF-8 can only have come from that path, so it maps back byte for
 * byte; anything else is text and is encoded as UTF-8. Remove once strings are byte strings.
 */
final class BinaryStrings {

    private BinaryStrings() {
    }

    static byte[] bytes(String s) {
        boolean latin1 = true;
        boolean high = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) {
                latin1 = false;
                break;
            }
            if (c >= 0x80) {
                high = true;
            }
        }
        if (!latin1 || !high) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        byte[] raw = s.getBytes(StandardCharsets.ISO_8859_1);
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
            return s.getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return raw;
        }
    }
}
