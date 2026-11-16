package dev.phpruntime;

import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ByteStringsTest {
    @Test
    void conversionsPreserveAllBytes() {
        byte[] raw = new byte[256];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        String php = PhpStrings.of(raw);
        assertEquals(256, php.length());
        assertArrayEquals(raw, PhpStrings.bytes(php));
        assertEquals("\u00c3\u00a9\u00f0\u009f\u0098\u0080", PhpStrings.fromJava("é😀"));
        assertEquals("é😀", PhpStrings.toJava(PhpStrings.fromJava("é😀")));
        assertEquals("\uFFFD", PhpStrings.toJava("\u00ff"));
    }

    @Test
    void strictUtf8AndOffsets() {
        String text = PhpStrings.fromJava("é😀a");
        assertTrue(PhpStrings.isUtf8(text));
        assertEquals(3, PhpStrings.utf8Length(text));
        for (String invalid : new String[]{"\u00ff", "\u00c0\u0080", "\u00ed\u00a0\u0080", "\u00f4\u0090\u0080\u0080", "\u00e2\u0082"}) {
            assertFalse(PhpStrings.isUtf8(invalid));
            assertEquals(invalid.length(), PhpStrings.utf8Length(invalid));
        }
        var offsets = new PhpStrings.Utf8Offsets("é😀a");
        assertEquals(2, offsets.toBytes(1));
        assertEquals(6, offsets.toBytes(3));
        assertEquals(1, offsets.toChars(2));
        assertEquals(3, offsets.toChars(6));
        assertEquals(-1, offsets.toChars(3));
    }

    @Test
    void builtinInvariantNamesTheOrigin() {
        assertTrue(PhpStrings.CHECK);
        var builtin = new Builtins.Def("broken_boundary", 0, 0, 0, (ctx, args) -> "😀");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> builtin.call(null, new Object[0]));
        assertTrue(error.getMessage().contains("broken_boundary()"));
    }

    @Test
    void javaCharacterSourcesAreEncodedOnce() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder("php").allowIO(IOAccess.ALL).out(out).build()) {
            ctx.eval(Source.newBuilder("php", "<?php echo 'é😀', \"\\xFF\";", "Java source").build());
        }
        byte[] expected = new byte[]{(byte) 0xc3, (byte) 0xa9, (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80, (byte) 0xff};
        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    void nonAsciiPathsAndHeredocEscapes(@TempDir Path dir) throws Exception {
        Path nested = Files.createDirectory(dir.resolve("café😀"));
        Files.writeString(nested.resolve("part.php"), "<?php echo 'é😀', \"\\xFF\";", StandardCharsets.UTF_8);
        Path main = Files.writeString(nested.resolve("main.php"), """
                <?php
                include __DIR__ . '/part.php';
                echo <<<END
                \\u{1F600}\\xFF
                END;
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            var result = engine.execute(main, new PhpRequest());
            assertEquals(200, result.status(), result.bodyText());
            assertArrayEquals(new byte[]{(byte) 0xc3, (byte) 0xa9, (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80, (byte) 0xff,
                    (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80, (byte) 0xff}, result.body());
        }
    }
}
