package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs src/test/resources/php-mysql/*.php against a live MySQL/MariaDB and compares with the
 * output real PHP's mysqli produced against the same server. Needs BLITZ_MYSQL_HOST (and
 * optionally BLITZ_MYSQL_PORT, _USER, _PASSWORD, _DB); skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "BLITZ_MYSQL_HOST", matches = ".+")
class MysqliConformanceTest {

    private static final Path DIR = Path.of("src/test/resources/php-mysql");
    private static PhpEngine engine;

    @BeforeAll
    static void setUp() {
        engine = new PhpEngine(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @TestFactory
    Stream<DynamicTest> fixtures() throws IOException {
        return Files.list(DIR)
                .filter(p -> p.getFileName().toString().matches("\\d+-.*\\.php"))
                .sorted()
                .map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(p)));
    }

    private static void check(Path script) throws Exception {
        byte[] expected;
        if (script.getFileName().toString().equals("08-byte-roundtrip.php")) {
            // Random BLOBs are checked inside the script, not printed. Generate the reference
            // against the same live server rather than committing an unverified expected file.
            Process php = new ProcessBuilder("/usr/bin/php", script.toString()).start();
            if (!php.waitFor(30, TimeUnit.SECONDS)) {
                php.destroyForcibly();
                throw new AssertionError("reference PHP timed out");
            }
            expected = php.getInputStream().readAllBytes();
            String errors = new String(php.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, php.exitValue(), errors);
        } else {
            expected = Files.readAllBytes(Path.of(script.toString().replaceAll("\\.php$", ".out")));
        }
        PhpEngine.Result result = engine.execute(script, new PhpRequest());
        assertArrayEquals(expected, result.body(), "output of " + script.getFileName() + "\nstderr: " + result.errors());
    }

    /**
     * A query waiting on the server gives up the CPU slot: with one slot, a hello request
     * finishes while SELECT SLEEP(2) runs, and the sleep does not count toward the
     * execution-time budget.
     */
    @Test
    void slowQueryReleasesTheCpuSlot(@TempDir Path dir) throws Exception {
        Path connect = DIR.resolve("inc_connect.php").toAbsolutePath();
        Path slow = Files.writeString(dir.resolve("slow.php"), "<?php require '" + connect + "';\n"
                + "$db = blitz_connect();\n"
                + "$r = mysqli_query($db, 'SELECT SLEEP(2) AS s');\n"
                + "echo mysqli_fetch_row($r)[0];\n");
        Path hello = Files.writeString(dir.resolve("hello.php"), "<?php echo 'hello';");
        try (Scheduler scheduler = new Scheduler(1, 10, 1000); PhpEngine eng = new PhpEngine(scheduler)) {
            CompletableFuture<PhpEngine.Result> query = CompletableFuture.supplyAsync(() -> run(eng, slow));
            Thread.sleep(500); // the query is now waiting on the server

            long start = System.nanoTime();
            PhpEngine.Result greeting = run(eng, hello);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals("hello", greeting.bodyText());
            assertTrue(millis < 500, "hello took " + millis + "ms while a query held the only CPU slot");

            PhpEngine.Result done = query.get(10, TimeUnit.SECONDS);
            assertEquals(200, done.status(), done.bodyText() + done.errors());
            assertEquals("0", done.bodyText());
        }
    }

    private static PhpEngine.Result run(PhpEngine eng, Path script) {
        try {
            return eng.execute(script, new PhpRequest());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
