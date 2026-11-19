package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
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

    private static final String POOL_URL = "jdbc:mariadb://127.0.0.1:33307/wp";

    private static final String FIRST_POOL_SCRIPT = """
            <?php
            mysqli_report(MYSQLI_REPORT_OFF);
            $db = mysqli_init();
            if (!@mysqli_real_connect($db, '127.0.0.1', 'root', 'wp', 'wp', 33307)) {
                echo 'failed=', mysqli_connect_errno(), PHP_EOL;
                exit(1);
            }
            $r = mysqli_query($db, 'SELECT CONNECTION_ID()');
            echo 'id=', mysqli_fetch_row($r)[0], PHP_EOL;
            $vars = mysqli_fetch_row(mysqli_query($db, 'SELECT @@SESSION.sql_mode, @@character_set_client, '
                . '@@character_set_connection, @@character_set_results, @@collation_connection, @@autocommit, '
                . '@@time_zone, @@transaction_isolation, @@SESSION.session_track_system_variables'));
            $encoded = [];
            foreach ($vars as $v) {
                $encoded[] = base64_encode($v);
            }
            echo 'session=', implode(',', $encoded), PHP_EOL;
            echo 'sqlmode=', $vars[0], PHP_EOL;
            mysqli_query($db, 'DROP TABLE IF EXISTS blitz_pool_probe');
            mysqli_query($db, 'CREATE TABLE blitz_pool_probe (id INT AUTO_INCREMENT PRIMARY KEY, v VARCHAR(32)) ENGINE=InnoDB');
            mysqli_query($db, 'START TRANSACTION');
            mysqli_query($db, "INSERT INTO blitz_pool_probe (v) VALUES ('rolled-back')");
            mysqli_query($db, 'SET @x = 1');
            mysqli_query($db, "SET SESSION sql_mode = ''");
            mysqli_query($db, 'CREATE TEMPORARY TABLE blitz_pool_tmp (id INT)');
            $r = mysqli_query($db, "SELECT GET_LOCK('blitz_pool_lock', 0)");
            echo 'lock=', mysqli_fetch_row($r)[0], PHP_EOL;
            $r = mysqli_query($db, 'SELECT LAST_INSERT_ID()');
            echo 'lastid=', mysqli_fetch_row($r)[0], PHP_EOL;
            """;

    private static final String SECOND_POOL_SCRIPT = """
            <?php
            mysqli_report(MYSQLI_REPORT_OFF);
            $db = mysqli_init();
            if (!@mysqli_real_connect($db, '127.0.0.1', 'root', 'wp', 'wp', 33307)) {
                echo 'failed=', mysqli_connect_errno(), PHP_EOL;
                exit(1);
            }
            $r = mysqli_query($db, 'SELECT CONNECTION_ID()');
            echo 'id=', mysqli_fetch_row($r)[0], PHP_EOL;
            $vars = mysqli_fetch_row(mysqli_query($db, 'SELECT @@SESSION.sql_mode, @@character_set_client, '
                . '@@character_set_connection, @@character_set_results, @@collation_connection, @@autocommit, '
                . '@@time_zone, @@transaction_isolation, @@SESSION.session_track_system_variables'));
            $encoded = [];
            foreach ($vars as $v) {
                $encoded[] = base64_encode($v);
            }
            echo 'session=', implode(',', $encoded), PHP_EOL;
            echo 'sqlmode=', $vars[0], PHP_EOL;
            $r = mysqli_query($db, "SELECT COUNT(*) FROM blitz_pool_probe WHERE v = 'rolled-back'");
            echo 'rows=', mysqli_fetch_row($r)[0], PHP_EOL;
            $r = mysqli_query($db, 'SELECT @x IS NULL');
            echo 'uservar=', mysqli_fetch_row($r)[0], PHP_EOL;
            $r = @mysqli_query($db, 'SELECT COUNT(*) FROM blitz_pool_tmp');
            echo 'temp=', ($r === false ? 'gone' : 'present'), PHP_EOL;
            $r = mysqli_query($db, "SELECT IS_USED_LOCK('blitz_pool_lock') IS NULL");
            echo 'lock=', mysqli_fetch_row($r)[0], PHP_EOL;
            $r = mysqli_query($db, 'SELECT LAST_INSERT_ID()');
            echo 'lastid=', mysqli_fetch_row($r)[0], PHP_EOL;
            mysqli_query($db, 'DROP TABLE IF EXISTS blitz_pool_probe');
            """;

    /**
     * Two simulated requests share one pooled connection. The second must get the first's
     * physical connection and a session equal to a fresh one: no open transaction, user
     * variables, session sql_mode, temporary table, named lock or last insert id.
     */
    @Test
    void pooledConnectionResetsTheSession(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(wpServerAvailable(), "MariaDB on 127.0.0.1:33307 is not available");
        Path first = Files.writeString(dir.resolve("pool-first.php"), FIRST_POOL_SCRIPT);
        Path second = Files.writeString(dir.resolve("pool-second.php"), SECOND_POOL_SCRIPT);

        PhpEngine.Result one = engine.execute(first, new PhpRequest());
        assertEquals(200, one.status(), one.bodyText() + one.errors());
        PhpEngine.Result two = engine.execute(second, new PhpRequest());
        assertEquals(200, two.status(), two.bodyText() + two.errors());

        Map<String, String> a = outputFields(one.bodyText());
        Map<String, String> b = outputFields(two.bodyText());
        String context = "\nfirst: " + one.bodyText() + "second: " + two.bodyText();
        assertEquals(a.get("id"), b.get("id"), "the second request did not reuse the pooled connection" + context);
        assertEquals(a.get("session"), b.get("session"), "the reset session differs from a fresh one" + context);
        assertEquals(a.get("sqlmode"), b.get("sqlmode"), "the session sql_mode was not restored" + context);
        assertEquals("1", a.get("lock"), "the first request did not take the named lock" + context);
        assertTrue(!"0".equals(a.get("lastid")), "the first request did not set LAST_INSERT_ID" + context);
        assertEquals("0", b.get("rows"), "the uncommitted transaction was not rolled back" + context);
        assertEquals("1", b.get("uservar"), "the user variable survived the reset" + context);
        assertEquals("gone", b.get("temp"), "the temporary table survived the reset" + context);
        assertEquals("1", b.get("lock"), "the named lock survived the reset" + context);
        assertEquals("0", b.get("lastid"), "LAST_INSERT_ID survived the reset" + context);
    }

    private static boolean wpServerAvailable() {
        Properties props = new Properties();
        props.setProperty("user", "root");
        props.setProperty("password", "wp");
        props.setProperty("connectTimeout", "1000");
        try {
            Connection c = new org.mariadb.jdbc.Driver().connect(POOL_URL, props);
            if (c == null) {
                return false;
            }
            c.close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Map<String, String> outputFields(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return fields;
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
