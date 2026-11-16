package dev.phpruntime;

import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.host.PhpDispatcher;
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
import java.util.Map;

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
        for (String invalid : new String[]{"\u00ff", "\u00c0\u0080", "\u00ed\u00a0\u0080", "\u00f4\u0090\u0080\u0080"}) {
            assertFalse(PhpStrings.isUtf8(invalid));
            assertEquals(invalid.length(), PhpStrings.utf8Length(invalid));
        }
        assertEquals(1, PhpStrings.utf8Length("\u00e2\u0082"));
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
    void fileSourcesDefaultToBytesForPolyglotEmbedders(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("text.php"), "<?php echo 'é😀', \"\\xFF\";");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder("php").allowIO(IOAccess.ALL).out(out).build()) {
            ctx.eval(Source.newBuilder("php", script.toFile()).build());
        }
        assertArrayEquals(PhpStrings.bytes(PhpStrings.fromJava("é😀") + "\u00ff"), out.toByteArray());
    }

    @Test
    void byteCaseFoldingAndSubstringsDoNotTouchNonAscii() {
        String lower = "already lower\u00c9\u00df";
        assertSame(lower, PhpStrings.lower(lower));
        assertEquals("abc\u00c9", PhpStrings.lower("ABC\u00c9"));
        assertEquals("ABC\u00e9", PhpStrings.upper("abc\u00e9"));
        assertFalse(PhpStrings.equalsIgnoreCase("\u00c9", "\u00e9"));
        String s = PhpStrings.fromJava("é😀éß");
        assertEquals(PhpStrings.fromJava("😀é"), PhpStrings.utf8Substr(s, 1, 3L, "?"));
        assertEquals("?X", PhpStrings.utf8Substr("\u00e2\u0082X", 0, 2L, "?"));
    }

    @Test
    void sharedRegexCacheKeepsOffsetsAndSettingsRequestLocal(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("concurrent.php"), """
                <?php
                $text = $_GET['text'];
                preg_match_all('/./u', $text, $matches, PREG_OFFSET_CAPTURE);
                foreach ($matches[0] as $pair) { echo bin2hex($pair[0]), '@', $pair[1], ':'; }
                echo mb_internal_encoding(), ':', preg_last_error();
                mb_internal_encoding('ISO-8859-1');
                preg_match('/./u', "\\xFF");
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                var jobs = new java.util.ArrayList<java.util.concurrent.Future<byte[]>>();
                for (int i = 0; i < 24; i++) {
                    String text = i % 2 == 0 ? "é😀a" : "😀éb";
                    jobs.add(workers.submit(() -> {
                        PhpRequest request = new PhpRequest();
                        request.get.put("text", PhpStrings.fromJava(text));
                        var result = engine.execute(script, request);
                        assertEquals(200, result.status(), result.bodyText() + result.errors());
                        return result.body();
                    }));
                }
                for (int i = 0; i < jobs.size(); i++) {
                    String expected = i % 2 == 0 ? "c3a9@0:f09f9880@2:61@6:UTF-8:0" : "f09f9880@0:c3a9@4:62@6:UTF-8:0";
                    assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), jobs.get(i).get());
                }
            } finally {
                workers.shutdown();
            }
        }
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

    @Test
    void dispatcherPreservesQueryPostCookiesAndRawBody(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("é.php"), """
                <?php
                echo bin2hex($_GET['text']), ':', bin2hex($_GET['raw']), "\\n";
                echo bin2hex($_POST['text']), ':', bin2hex($_POST['raw']), "\\n";
                echo bin2hex($_COOKIE['text']), ':', bin2hex($_COOKIE['raw']), "\\n";
                echo bin2hex(file_get_contents('php://input')), "\\n";
                echo bin2hex($_SERVER['HTTP_X_BYTES']), "\\n";
                echo bin2hex(basename($_SERVER['SCRIPT_FILENAME']));
                """);
        byte[] body = PhpStrings.bytes("text=\u00c3\u00a9&raw=%FF");
        try (PhpEngine engine = new PhpEngine(null)) {
            PhpDispatcher dispatcher = new PhpDispatcher(dir, engine);
            var request = new PhpDispatcher.Request("POST", "/%C3%A9.php", "text=%C3%A9&raw=%FF", "HTTP/1.1",
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "Cookie", "text=%C3%A9; raw=%FF",
                            "X-Bytes", "\u00c3\u00a9\u00ff"), body, "127.0.0.1", "localhost", 80);
            var response = dispatcher.handle(request);
            String expected = "c3a9:ff\nc3a9:ff\nc3a9:ff\n746578743dc3a9267261773d254646\nc3a9ff\nc3a92e706870";
            assertEquals(200, response.status());
            assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), response.body());
        }
    }

    @Test
    void dispatcherRawInputAndOutputAreBinary(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("raw.php"), "<?php echo file_get_contents('php://input');");
        byte[] body = new byte[]{(byte) 0xff, 0, (byte) 0xc3, (byte) 0xa9, (byte) 0x80};
        try (PhpEngine engine = new PhpEngine(null)) {
            var dispatcher = new PhpDispatcher(dir, engine);
            var request = new PhpDispatcher.Request("POST", "/raw.php", "", "HTTP/1.1", Map.of(), body,
                    "127.0.0.1", "localhost", 80);
            var response = dispatcher.handle(request);
            assertEquals(200, response.status());
            assertArrayEquals(body, response.body());
        }
    }

    @Test
    void hostProvidedEnvironmentIsJavaText(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("env.php"), "<?php echo bin2hex($_ENV['clé']);");
        PhpRequest request = new PhpRequest();
        request.environment.put("clé", "é😀");
        try (PhpEngine engine = new PhpEngine(null)) {
            var result = engine.execute(script, request);
            assertEquals(200, result.status(), result.bodyText() + result.errors());
            assertArrayEquals("c3a9f09f9880".getBytes(StandardCharsets.US_ASCII), result.body());
        }
    }

    @Test
    void pathsAndDiagnosticsHaveExplicitBoundaries(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("é😀"), "payload");
        String code = """
                <?php
                $p = __DIR__ . '/é😀';
                echo bin2hex(basename(realpath($p))), "\\n";
                $found = glob(__DIR__ . '/*');
                foreach ($found as $f) { if (basename($f) === 'é😀') { echo bin2hex(basename($f)), "\\n"; } }
                $dir = opendir(__DIR__);
                while (false !== ($f = readdir($dir))) { if ($f === 'é😀') { echo bin2hex($f), "\\n"; } }
                closedir($dir);
                error_log("é\\xFF");
                echo 'é';
                """;
        Path script = Files.writeString(dir.resolve("paths.php"), code);
        try (PhpEngine engine = new PhpEngine(null)) {
            var result = engine.execute(script, new PhpRequest());
            assertEquals(200, result.status(), result.bodyText() + result.errors());
            assertArrayEquals(PhpStrings.bytes("c3a9f09f9880\nc3a9f09f9880\nc3a9f09f9880\n" + PhpStrings.fromJava("é")), result.body());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder("php").allowIO(IOAccess.ALL).out(new ByteArrayOutputStream()).err(err).build()) {
            ctx.eval(Source.newBuilder("php", "<?php error_log(\"é\\xFF\");", "diagnostic").build());
        }
        assertArrayEquals(PhpStrings.bytes(PhpStrings.fromJava("é") + "\u00ff\n"), err.toByteArray());
    }
}
