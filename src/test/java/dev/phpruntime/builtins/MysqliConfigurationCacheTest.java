package dev.phpruntime.builtins;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parsed Connector/J configuration is shared per (URL, driver properties). Many connects
 * with the same settings must parse once, and a cached configuration must never leak a user,
 * database, charset or session mode into a connection made with different settings.
 */
@EnabledIfEnvironmentVariable(named = "BLITZ_MYSQL_HOST", matches = ".+")
class MysqliConfigurationCacheTest {

    private static PhpEngine engine;

    @BeforeAll
    static void setUp() {
        engine = new PhpEngine(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @Test
    void sameSettingsParseOnceAndDifferentSettingsKeepTheirOwnSession(@TempDir Path dir) throws Exception {
        String host = env("BLITZ_MYSQL_HOST", "127.0.0.1");
        String port = env("BLITZ_MYSQL_PORT", "33308");
        String user = env("BLITZ_MYSQL_USER", "root");
        String password = env("BLITZ_MYSQL_PASSWORD", "wp");
        String database = env("BLITZ_MYSQL_DB", "blitz");
        String script = """
                <?php
                mysqli_report(MYSQLI_REPORT_OFF);
                $host = '%s';
                $port = %s;
                $user = '%s';
                $pass = '%s';
                $db = '%s';

                function show($link, $tag) {
                    $r = mysqli_query($link, 'SELECT CURRENT_USER(), DATABASE(), '
                        . '@@character_set_client, @@SESSION.sql_mode');
                    $row = mysqli_fetch_row($r);
                    mysqli_free_result($r);
                    echo $tag, ' user=', $row[0], ' db=', $row[1] === null ? 'NULL' : $row[1],
                        ' charset=', $row[2], ' mode=', $row[3], "\\n";
                }

                for ($i = 0; $i < 20; $i++) {
                    $link = mysqli_connect($host, $user, $pass, $db, $port);
                    show($link, 'same');
                    mysqli_close($link);
                }

                for ($i = 0; $i < 5; $i++) {
                    $link = mysqli_connect($host, $user, $pass, null, $port);
                    show($link, 'nodb');
                    mysqli_close($link);
                }

                $link = @mysqli_connect($host, $user, 'not-the-password', $db, $port);
                echo 'bad=', $link === false ? mysqli_connect_errno() : 'connected', "\\n";

                for ($i = 0; $i < 4; $i++) {
                    $link = mysqli_init();
                    mysqli_options($link, MYSQLI_OPT_CONNECT_TIMEOUT, $i < 2 ? 1 : 2);
                    mysqli_real_connect($link, $host, $user, $pass, $db, $port);
                    show($link, 'option');
                    mysqli_close($link);
                }

                $link = mysqli_connect($host, $user, $pass, $db, $port);
                mysqli_set_charset($link, 'latin1');
                $r = mysqli_query($link, 'SELECT @@character_set_client');
                $row = mysqli_fetch_row($r);
                mysqli_free_result($r);
                echo 'changed=', $row[0], "\\n";
                mysqli_close($link);

                $link = mysqli_connect($host, $user, $pass, $db, $port);
                show($link, 'after');
                mysqli_close($link);
                """.formatted(host, port, user, password, database);
        Path file = Files.writeString(dir.resolve("cache.php"), script);

        long before = MysqliFunctions.configurationsParsedForTest();
        PhpEngine.Result first = engine.execute(file, new PhpRequest());
        long parsed = MysqliFunctions.configurationsParsedForTest() - before;
        assertEquals(200, first.status(), first.bodyText() + first.errors());
        assertTrue(parsed > 0, "the first run did not parse any configuration");

        List<String> lines = new ArrayList<>(first.bodyText().lines().toList());
        assertEquals(32, lines.size(), first.bodyText());
        for (int i = 0; i < 20; i++) {
            assertSession(lines.get(i), "same", user, database, first.bodyText());
        }
        for (int i = 20; i < 25; i++) {
            assertSession(lines.get(i), "nodb", user, null, first.bodyText());
        }
        assertEquals("bad=1045", lines.get(25), first.bodyText());
        for (int i = 26; i < 30; i++) {
            assertSession(lines.get(i), "option", user, database, first.bodyText());
        }
        assertEquals("changed=latin1", lines.get(30), first.bodyText());
        assertSession(lines.get(31), "after", user, database, first.bodyText());

        long afterFirst = MysqliFunctions.configurationsParsedForTest();
        PhpEngine.Result second = engine.execute(file, new PhpRequest());
        assertEquals(200, second.status(), second.bodyText() + second.errors());
        assertEquals(afterFirst, MysqliFunctions.configurationsParsedForTest(),
                "a repeated run with the same settings parsed a configuration again");
        assertEquals(first.bodyText(), second.bodyText());
    }

    private static void assertSession(String line, String tag, String user, String database, String body) {
        assertTrue(line.startsWith(tag + " user=" + user + "@"), line + "\n" + body);
        assertTrue(line.contains(" db=" + (database == null ? "NULL" : database) + " "), line + "\n" + body);
        assertTrue(line.contains(" charset=utf8mb4 "), line + "\n" + body);
        assertTrue(line.contains(" mode=STRICT_TRANS_TABLES,"), line + "\n" + body);
        assertFalse(line.contains("IGNORE_SPACE"), line + "\n" + body);
    }
}
