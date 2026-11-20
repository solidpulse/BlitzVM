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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs src/test/resources/php-mysql/pdo-*.php through both real PHP (for the committed .out
 * files) and BlitzVM against a live MySQL/MariaDB, comparing byte for byte. Needs
 * BLITZ_MYSQL_HOST (and optionally _PORT, _USER, _PASSWORD, _DB); skipped otherwise.
 *
 * <p>The fixtures share the directory and the connection helper with the mysqli suite, but
 * their names start with "pdo-" so the mysqli test's NNN- discovery does not pick them up.
 */
@EnabledIfEnvironmentVariable(named = "BLITZ_MYSQL_HOST", matches = ".+")
class PdoConformanceTest {

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
                .filter(p -> p.getFileName().toString().matches("pdo-\\d+-.*\\.php"))
                .sorted()
                .map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(p)));
    }

    private static void check(Path script) throws Exception {
        byte[] expected = Files.readAllBytes(Path.of(script.toString().replaceAll("\\.php$", ".out")));
        PhpEngine.Result result = engine.execute(script, new PhpRequest());
        assertArrayEquals(expected, result.body(), "output of " + script.getFileName()
                + "\nstderr: " + result.errors());
    }

    /**
     * A query waiting on the server gives up the CPU slot: with one slot, a hello request
     * finishes while SELECT SLEEP(2) runs.
     */
    @Test
    void slowQueryReleasesTheCpuSlot(@TempDir Path dir) throws Exception {
        Path slow = Files.writeString(dir.resolve("pdo-slow.php"), """
                <?php
                require '%s';
                $p = blitz_pdo();
                echo $p->query('SELECT SLEEP(2)')->fetchColumn();
                """.formatted(DIR.resolve("pdo_connect.php").toAbsolutePath()));
        Path hello = Files.writeString(dir.resolve("hello.php"), "<?php echo 'hello';");
        try (Scheduler scheduler = new Scheduler(1, 10, 1000); PhpEngine eng = new PhpEngine(scheduler)) {
            CompletableFuture<PhpEngine.Result> query = CompletableFuture.supplyAsync(() -> run(eng, slow));
            Thread.sleep(500);
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
