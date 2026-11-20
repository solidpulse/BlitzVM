package dev.phpruntime.builtins;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.snapshot.SnapshotGeneration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks which statements advance the snapshot generation. Uses the live MariaDB from
 * BLITZ_MYSQL_HOST and only the database named by BLITZ_MYSQL_DB, with a snap_ table.
 */
@EnabledIfEnvironmentVariable(named = "BLITZ_MYSQL_HOST", matches = ".+")
class SnapshotGenerationTest {

    private static final String CONNECT = """
            $host = getenv('BLITZ_MYSQL_HOST');
            $port = (int) (getenv('BLITZ_MYSQL_PORT') ?: 33307);
            $user = getenv('BLITZ_MYSQL_USER') ?: 'root';
            $password = getenv('BLITZ_MYSQL_PASSWORD') !== false ? getenv('BLITZ_MYSQL_PASSWORD') : 'wp';
            $db = 'blitz';
            mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);
            $link = mysqli_init();
            mysqli_real_connect($link, $host, $user, $password, $db, $port);
            """;

    @TempDir
    static Path tmp;

    private static PhpEngine engine;
    private static final AtomicInteger scripts = new AtomicInteger();

    @BeforeAll
    static void setUp() {
        engine = new PhpEngine(null);
    }

    @AfterAll
    static void tearDown() {
        try {
            if (Files.isDirectory(tmp)) {
                run("mysqli_query($link, 'DROP TABLE IF EXISTS snap_gen');");
            }
        } catch (RuntimeException | IOException | AssertionError ignored) {
            // the test table is dropped best effort; a leftover is removed by the next run
        }
        engine.close();
    }

    @Test
    void onlyStatementsThatCanChangeDataAdvanceTheGeneration() throws IOException {
        run("mysqli_query($link, 'DROP TABLE IF EXISTS snap_gen');"
                + "mysqli_query($link, 'CREATE TABLE snap_gen (id INT AUTO_INCREMENT PRIMARY KEY,"
                + " v VARCHAR(16)) ENGINE=InnoDB');");

        assertSame("SELECT 1");
        assertSame("SHOW TABLES");
        assertSame("DESCRIBE snap_gen");
        assertSame("EXPLAIN SELECT * FROM snap_gen");
        assertSame("USE `" + database() + "`");
        assertSame("BEGIN");
        assertSame("START TRANSACTION");
        assertSame("COMMIT");
        assertSame("ROLLBACK");
        assertSame("SET @snap_generation = 1");
        assertSame("SET SESSION sql_mode = 'STRICT_TRANS_TABLES'");

        assertAdvances("INSERT INTO snap_gen (v) VALUES ('a')");
        assertAdvances("UPDATE snap_gen SET v = 'b' WHERE v = 'a'");
        assertAdvances("DELETE FROM snap_gen WHERE v = 'b'");
        assertAdvances("REPLACE INTO snap_gen (id, v) VALUES (1, 'c')");
        assertAdvances("CREATE TABLE IF NOT EXISTS snap_gen (id INT) ENGINE=InnoDB");
        assertAdvances("DROP TABLE IF EXISTS snap_absent");

        long before = SnapshotGeneration.current();
        run("$stmt = mysqli_prepare($link, \"INSERT INTO snap_gen (v) VALUES ('p')\");"
                + "mysqli_stmt_execute($stmt);"
                + "mysqli_stmt_execute($stmt);"
                + "mysqli_stmt_close($stmt);");
        assertTrue(SnapshotGeneration.current() >= before + 2,
                "a prepared INSERT executed twice must advance twice");

        long pdoBefore = SnapshotGeneration.current();
        run("$pdo = new PDO('mysql:host=' . $host . ';port=' . $port . ';dbname=' . $db,"
                + " $user, $password); $pdo->exec(\"INSERT INTO snap_gen (v) VALUES ('q')\");");
        assertTrue(SnapshotGeneration.current() > pdoBefore, "PDO::exec must advance the generation");

        run("mysqli_query($link, 'DROP TABLE IF EXISTS snap_gen');");
    }

    private static String database() {
        return "blitz";
    }

    private static String literal(String sql) {
        return "'" + sql.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void assertSame(String statement) throws IOException {
        long before = SnapshotGeneration.current();
        run("mysqli_query($link, " + literal(statement) + ");");
        assertEquals(before, SnapshotGeneration.current(), statement + " must not advance the generation");
    }

    private void assertAdvances(String statement) throws IOException {
        long before = SnapshotGeneration.current();
        run("mysqli_query($link, " + literal(statement) + ");");
        assertTrue(SnapshotGeneration.current() > before, statement + " must advance the generation");
    }

    private static void run(String body) throws IOException {
        Path script = tmp.resolve("gen-" + scripts.incrementAndGet() + ".php");
        Files.writeString(script, "<?php\n" + CONNECT + body + "\n");
        PhpEngine.Result result = engine.execute(script, new PhpRequest());
        assertTrue(result.status() < 500, result.bodyText() + "\n" + result.errors());
    }
}
