package dev.phpruntime.snapshot;

import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs every case under src/test/resources/snapshot through the capture/restore harness. */
class SnapshotCheckTest {

    private static final Path CASES = Path.of("src/test/resources/snapshot");
    private static Engine engine;

    @BeforeAll
    static void setUp() {
        engine = SnapshotCheck.newEngine();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @TestFactory
    Stream<DynamicTest> cases() throws IOException {
        try (Stream<Path> dirs = Files.list(CASES)) {
            return dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("entry.php")))
                    .sorted()
                    .map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> check(dir)))
                    .toList()
                    .stream();
        }
    }

    /** Two threads restore the same snapshot at once; neither may see the other's copies. */
    @Test
    void concurrentRestoresShareTheSnapshotSafely() throws Exception {
        Path dir = CASES.resolve("objects");
        Snapshot snapshot = SnapshotCheck.captureSnapshot(engine,
                dir.resolve("boot.php"), dir.resolve("entry.php"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> SnapshotCheck.restoredOutput(engine,
                    dir.resolve("boot.php"), dir.resolve("entry.php"), snapshot));
            Future<String> second = pool.submit(() -> SnapshotCheck.restoredOutput(engine,
                    dir.resolve("boot.php"), dir.resolve("entry.php"), snapshot));
            String a = first.get(30, TimeUnit.SECONDS);
            String b = second.get(30, TimeUnit.SECONDS);
            assertEquals(a, b, "two concurrent restores must produce the same output");
            assertTrue(a.contains("cycle=yes"), a);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void check(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve("requires-mysql"))) {
            Assumptions.assumeTrue(System.getenv("BLITZ_MYSQL_HOST") != null
                    && !System.getenv("BLITZ_MYSQL_HOST").isEmpty(),
                    "needs BLITZ_MYSQL_HOST for a live database");
        }
        Path expectedFile = dir.resolve("expected-refusal.txt");
        SnapshotCheck.Result result = SnapshotCheck.check(engine,
                dir.resolve("boot.php"), dir.resolve("entry.php"));

        if (Files.isRegularFile(expectedFile)) {
            String expected = Files.readString(expectedFile).trim();
            assertTrue(result.refused(), () -> "expected a refusal, got " + describe(result));
            assertNotNull(result.refusal());
            assertTrue(result.refusalReasons().stream().anyMatch(reason -> reason.equals(expected)),
                    () -> "expected reason \"" + expected + "\" in " + result.refusalReasons());
            return;
        }

        assertFalse(result.refused(), () -> "unexpected refusal: " + result.refusalReasons());
        assertTrue(result.ok(), () -> describe(result) + "\n" + String.join("\n", result.differences()));
        assertEquals(result.fullOutput(), result.firstOutput(), "first restore output");
        assertEquals(result.fullOutput(), result.secondOutput(), "second restore output");
        assertEquals(result.fullFingerprint(), result.firstFingerprint(), "first restore fingerprint");
        assertEquals(result.fullFingerprint(), result.secondFingerprint(), "second restore fingerprint");
        if (dir.getFileName().toString().equals("abort-skips-finally")) {
            assertEquals("", result.templateOutput(),
                    "the template run must not run PHP finally blocks after the abort");
        }
    }

    private static String describe(SnapshotCheck.Result result) {
        StringBuilder text = new StringBuilder();
        text.append("full output: ").append(result.fullOutput().replace("\n", "\\n"));
        text.append("\nfirst output: ").append(result.firstOutput().replace("\n", "\\n"));
        text.append("\nsecond output: ").append(result.secondOutput().replace("\n", "\\n"));
        if (result.error() != null) {
            text.append("\nerror: ").append(result.error());
        }
        List<String> differences = result.differences();
        if (!differences.isEmpty()) {
            text.append("\ndifferences: ").append(String.join("; ", differences));
        }
        return text.toString();
    }
}
