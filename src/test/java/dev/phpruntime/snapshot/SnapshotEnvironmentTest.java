package dev.phpruntime.snapshot;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot-added environment state: replayed on restore, never over a key the request supplies, and
 * still refused when boot changes a key the boot include started with.
 */
class SnapshotEnvironmentTest {

    @TempDir
    Path temp;

    @Test
    void bootAddedServerEnvAndPutenvAreReplayed() throws Exception {
        Path entry = Path.of("src/test/resources/snapshot/environment/entry.php").toRealPath();
        Path boot = entry.getParent().resolve("boot.php").toRealPath();
        try (PhpEngine engine = new PhpEngine(null)) {
            SnapshotRun template = SnapshotRun.template(boot.toString());
            engine.execute(entry, SnapshotCheck.request(entry), template);
            assertNull(template.refusal(), "capture was refused");
            assertNotNull(template.snapshot());

            SnapshotRun restored = SnapshotRun.restored(boot.toString(), template.snapshot());
            String body = engine.execute(entry, SnapshotCheck.request(entry), restored).bodyText();
            assertTrue(body.contains("server=server"), body);
            assertTrue(body.contains("env=server"), body);
            assertTrue(body.contains("getenv='server'"), body);
            assertTrue(body.contains("server only=server-only"), body);
            // No request defines the header key, so the replay installs all three views.
            assertTrue(body.contains("request key=boot"), body);
            assertTrue(body.contains("request env=boot"), body);
            assertTrue(body.contains("request getenv='boot'"), body);
        }
    }

    @Test
    void aRequestSuppliedKeyIsNotOverwritten() throws Exception {
        Path entry = Path.of("src/test/resources/snapshot/environment/entry.php").toRealPath();
        Path boot = entry.getParent().resolve("boot.php").toRealPath();
        try (PhpEngine engine = new PhpEngine(null)) {
            SnapshotRun template = SnapshotRun.template(boot.toString());
            engine.execute(entry, SnapshotCheck.request(entry), template);
            assertNotNull(template.snapshot(), "capture was refused: " + template.refusal());

            PhpRequest request = SnapshotCheck.request(entry);
            request.server.put("HTTP_X_REQUEST_KEY", "mine");
            SnapshotRun restored = SnapshotRun.restored(boot.toString(), template.snapshot());
            String body = engine.execute(entry, request, restored).bodyText();
            // The immutable loader would have skipped the variable in every adapter, so the
            // request keeps its own server value and neither $_ENV nor getenv gains boot's.
            assertTrue(body.contains("request key=mine"), body);
            assertTrue(body.contains("request env=missing"), body);
            assertTrue(body.contains("request getenv=false"), body);
            // The other boot-added keys are still replayed.
            assertTrue(body.contains("server=server"), body);
            assertTrue(body.contains("env=server"), body);
        }
    }

    @Test
    void aRequestSuppliedEnvKeyAlsoKeepsAllThreeViews() throws Exception {
        Path entry = Path.of("src/test/resources/snapshot/environment/entry.php").toRealPath();
        Path boot = entry.getParent().resolve("boot.php").toRealPath();
        try (PhpEngine engine = new PhpEngine(null)) {
            SnapshotRun template = SnapshotRun.template(boot.toString());
            engine.execute(entry, SnapshotCheck.request(entry), template);
            assertNotNull(template.snapshot(), "capture was refused: " + template.refusal());

            PhpRequest request = SnapshotCheck.request(entry);
            request.environment.put("BOOT_SERVER", "mine");
            SnapshotRun restored = SnapshotRun.restored(boot.toString(), template.snapshot());
            String body = engine.execute(entry, request, restored).bodyText();
            assertTrue(body.contains("server=missing"), body);
            assertTrue(body.contains("env=mine"), body);
            assertTrue(body.contains("getenv=false"), body);
        }
    }

    @Test
    void changingAPreBootEnvironmentKeyIsStillRefused() throws Exception {
        Path dir = app("boot changes a key the request supplied",
                "<?php\n$_SERVER['PRE_KEY'] = 'boot';\n",
                "<?php\n$_SERVER['PRE_KEY'] = 'request';\nrequire_once __DIR__ . '/boot.php';\necho 'pre=', $_SERVER['PRE_KEY'], \"\\n\";\n");
        Path entry = dir.resolve("entry.php");
        Path boot = dir.resolve("boot.php");
        try (PhpEngine engine = new PhpEngine(null)) {
            PhpRequest request = SnapshotCheck.request(entry);
            request.server.put("PRE_KEY", "request");
            SnapshotRun template = SnapshotRun.template(boot.toString());
            engine.execute(entry, request, template);
            assertNotNull(template.refusal(), "capture should have been refused");
            assertTrue(template.refusal().contains("boot changed the pre-boot global $_SERVER"),
                    template.refusal().toString());

            // The refused template run must not change what a later request computes.
            PhpRequest later = SnapshotCheck.request(entry);
            later.server.put("PRE_KEY", "request");
            String body = engine.execute(entry, later).bodyText();
            assertEquals("pre=boot\n", body);
        }
    }

    private Path app(String name, String boot, String entry) throws IOException {
        Path dir = temp.resolve(name.replaceAll("[^a-z]+", "-"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("boot.php"), boot, StandardCharsets.ISO_8859_1);
        Files.writeString(dir.resolve("entry.php"), entry, StandardCharsets.ISO_8859_1);
        return dir;
    }
}
