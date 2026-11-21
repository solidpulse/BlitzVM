package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs requests through {@link PhpEngine} with a pool of one (or four for the concurrency
 * case), so each request after the first reuses the same polyglot context. Every request
 * must still see exactly the state a fresh context would have.
 */
class ContextReuseTest {

    @TempDir
    Path dir;

    private Path write(String name, String code) throws IOException {
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent() == null ? dir : path.getParent());
        Files.writeString(path, code);
        return path;
    }

    private static PhpEngine.Result run(PhpEngine engine, Path script) throws IOException {
        return engine.execute(script, new PhpRequest());
    }

    @Test
    void globalsFunctionsClassesAndConstantsAreFresh() throws Exception {
        Path first = write("state-first.php", """
                <?php
                $foo = 'one';
                $GLOBALS['bar'] = 'two';
                define('ONLY_FIRST', 'yes');
                function reuseCounter() { static $n = 0; return ++$n; }
                class ReuseStatic { public static $v = 10; }
                reuseCounter();
                reuseCounter();
                ReuseStatic::$v = 99;
                echo 'counter=', reuseCounter(), "\\n";
                echo 'static=', ReuseStatic::$v, "\\n";
                echo 'const=', defined('ONLY_FIRST') ? 'yes' : 'no', "\\n";
                """);
        Path second = write("state-second.php", """
                <?php
                function reuseCounter() { static $n = 0; return ++$n; }
                class ReuseStatic { public static $v = 10; }
                echo 'foo=', isset($foo) ? 'set' : 'unset', "\\n";
                echo 'bar=', isset($GLOBALS['bar']) ? 'set' : 'unset', "\\n";
                echo 'const=', defined('ONLY_FIRST') ? 'yes' : 'no', "\\n";
                echo 'counter=', reuseCounter(), "\\n";
                echo 'static=', ReuseStatic::$v, "\\n";
                """);

        try (Scheduler scheduler = new Scheduler(1, 10, 0);
             PhpEngine engine = new PhpEngine(scheduler, 1)) {
            PhpEngine.Result one = run(engine, first);
            assertEquals(200, one.status(), one.bodyText() + one.errors());
            assertEquals("counter=3\nstatic=99\nconst=yes\n", one.bodyText());

            PhpEngine.Result two = run(engine, second);
            assertEquals(200, two.status(), two.bodyText() + two.errors());
            assertEquals("foo=unset\nbar=unset\nconst=no\ncounter=1\nstatic=10\n", two.bodyText());

            assertEquals(1, engine.contextsCreated(), "the second request did not reuse the pooled context");
        }
    }

    @Test
    void includesBuffersHandlersTimezonesAndResourcesAreFresh() throws Exception {
        write("inc/once.php", """
                <?php
                echo "included\\n";
                $onceValue = 42;
                """);
        Path first = write("state-first.php", """
                <?php
                include_once __DIR__ . '/inc/once.php';
                include_once __DIR__ . '/inc/once.php';
                echo 'once=', isset($onceValue) ? 'set' : 'unset', "\\n";
                echo 'get=', $_GET['a'], "\\n";
                echo 'post=', $_POST['b'], "\\n";
                echo 'cookie=', $_COOKIE['c'], "\\n";
                echo 'server=', $_SERVER['MARK'], "\\n";
                date_default_timezone_set('Europe/Berlin');
                spl_autoload_register(function ($name) { eval('class ' . $name . ' {}'); });
                $auto = new AutoThing();
                $file = fopen(__FILE__, 'r');
                opendir(__DIR__);
                ob_start();
                set_error_handler(function () { return true; });
                set_exception_handler(function ($e) { echo 'old-handler'; });
                register_shutdown_function(function () { echo "shutdown\\n"; });
                http_response_code(404);
                header('X-Leak: 1');
                echo 'tz=', date_default_timezone_get(), "\\n";
                echo 'autoload=', class_exists('AutoThing', false) ? 'yes' : 'no', "\\n";
                echo 'resource=', get_resource_id($file), "\\n";
                echo 'iniset=', ini_set('memory_limit', '64M') === false ? 'false' : 'true', "\\n";
                """);
        Path second = write("state-second.php", """
                <?php
                include_once __DIR__ . '/inc/once.php';
                echo 'once=', isset($onceValue) ? 'set' : 'unset', "\\n";
                echo 'get=', isset($_GET['a']) ? $_GET['a'] : 'none', "\\n";
                echo 'post=', isset($_POST['b']) ? $_POST['b'] : 'none', "\\n";
                echo 'cookie=', isset($_COOKIE['c']) ? $_COOKIE['c'] : 'none', "\\n";
                echo 'server=', $_SERVER['MARK'] ?? 'none', "\\n";
                echo 'tz=', date_default_timezone_get(), "\\n";
                echo 'autoloaders=', count(spl_autoload_functions()), "\\n";
                echo 'auto=', class_exists('AutoThing', true) ? 'yes' : 'no', "\\n";
                echo 'level=', ob_get_level(), "\\n";
                echo 'ini=', ini_get('memory_limit'), "\\n";
                echo 'status=', http_response_code(), "\\n";
                echo 'headers=', count(headers_list()), "\\n";
                $file = fopen(__FILE__, 'r');
                echo 'resource=', get_resource_id($file), "\\n";
                try { readdir(); } catch (Throwable $e) { echo 'readdir=', $e->getMessage(), "\\n"; }
                echo $undefinedVariable;
                """);

        PhpRequest requestOne = new PhpRequest();
        requestOne.get.put("a", "1");
        requestOne.post.put("b", "2");
        requestOne.headers.put("Cookie", "c=3");
        requestOne.server.put("MARK", "first");
        PhpRequest requestTwo = new PhpRequest();
        requestTwo.get.put("a", "9");
        requestTwo.post.put("b", "8");
        requestTwo.headers.put("Cookie", "c=7");
        requestTwo.server.put("MARK", "second");

        try (Scheduler scheduler = new Scheduler(1, 10, 0);
             PhpEngine engine = new PhpEngine(scheduler, 1)) {
            PhpEngine.Result one = engine.execute(first, requestOne);
            assertEquals(404, one.status(), one.bodyText() + one.errors());
            assertEquals("""
                    included
                    once=set
                    get=1
                    post=2
                    cookie=3
                    server=first
                    tz=Europe/Berlin
                    autoload=yes
                    resource=5
                    iniset=false
                    shutdown
                    """, one.bodyText());
            assertTrue(one.request().responseHeaders.stream().anyMatch(h -> h[0].equals("X-Leak")),
                    "the first request's header is missing");

            PhpEngine.Result two = engine.execute(second, requestTwo);
            assertEquals(200, two.status(), two.bodyText() + two.errors());
            assertEquals("""
                    included
                    once=set
                    get=9
                    post=8
                    cookie=7
                    server=second
                    tz=UTC
                    autoloaders=0
                    auto=no
                    level=0
                    ini=128M
                    status=200
                    headers=0
                    resource=5
                    readdir=readdir(): No resource supplied
                    """, two.bodyText());
            assertTrue(two.errors().contains("Undefined variable"),
                    "the first request's error handler survived: " + two.errors());
            assertEquals(1, engine.contextsCreated());

            // A leaked exception handler would swallow this instead of producing a fatal error.
            PhpEngine.Result three = run(engine, write("state-third.php",
                    "<?php throw new Exception('boom');"));
            assertEquals(500, three.status());
            assertTrue(three.bodyText().contains("Uncaught Exception: boom"), three.bodyText());
            assertFalse(three.bodyText().contains("old-handler"), three.bodyText());
        }
    }

    @Test
    void exitErrorAndTimeoutDiscardTheContext() throws Exception {
        Path exit = write("exit.php", "<?php echo 'before'; exit(7);");
        Path handled = write("handled.php", """
                <?php
                set_exception_handler(function ($e) { echo 'handled'; });
                throw new Exception('caught by the handler');
                """);
        Path fatal = write("fatal.php", "<?php throw new Exception('boom');");
        Path loop = write("loop.php", "<?php while (true) { }");
        Path clean = write("clean.php", "<?php echo 'clean';");

        try (Scheduler scheduler = new Scheduler(1, 10, 1000);
             PhpEngine engine = new PhpEngine(scheduler, 1)) {
            PhpEngine.Result one = run(engine, exit);
            assertEquals(200, one.status(), one.bodyText());
            assertEquals("before", one.bodyText());
            assertEquals(1, engine.contextsCreated());

            assertEquals("clean", run(engine, clean).bodyText());
            assertEquals(2, engine.contextsCreated(), "a context that ran exit() was reused");

            PhpEngine.Result two = run(engine, handled);
            assertEquals(200, two.status(), two.bodyText());
            assertEquals("handled", two.bodyText());
            assertEquals("clean", run(engine, clean).bodyText());
            assertEquals(3, engine.contextsCreated(), "a context with a handled uncaught error was reused");

            PhpEngine.Result three = run(engine, fatal);
            assertEquals(500, three.status());
            assertTrue(three.bodyText().contains("Uncaught Exception: boom"), three.bodyText());
            assertEquals("clean", run(engine, clean).bodyText());
            assertEquals(4, engine.contextsCreated(), "a context with an uncaught error was reused");

            PhpEngine.Result four = run(engine, loop);
            assertEquals(500, four.status());
            assertTrue(four.bodyText().contains("Maximum execution time of 1 second exceeded"), four.bodyText());
            assertEquals("clean", run(engine, clean).bodyText());
            assertEquals(5, engine.contextsCreated(), "a timed-out context was reused");
        }
    }

    @Test
    void javascriptRunsDoNotReuseThePhpPool() throws Exception {
        Path php = write("plain.php", "<?php echo 'php';");
        Path js = write("plain.js", "console.log('js');");

        try (Scheduler scheduler = new Scheduler(1, 10, 0);
             PhpEngine engine = new PhpEngine(scheduler, 1)) {
            assertEquals("php", run(engine, php).bodyText());
            assertEquals(1, engine.contextsCreated());
            assertEquals("js\n", run(engine, js).bodyText());
            assertEquals(1, engine.contextsCreated(), "a JavaScript run touched the PHP pool");
            assertEquals("php", run(engine, php).bodyText());
            assertEquals(1, engine.contextsCreated());
        }
    }

    @Test
    void concurrentRequestsOnAPoolOfFourKeepTheirOwnOutput() throws Exception {
        int requests = 4;
        int lines = 400;
        List<Path> scripts = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            char marker = (char) ('A' + i);
            scripts.add(write("concurrent-" + marker + ".php",
                    "<?php for ($i = 0; $i < " + lines + "; $i++) { echo '" + marker + "'; usleep(200); }"));
        }
        try (Scheduler scheduler = new Scheduler(4, 10, 0);
             PhpEngine engine = new PhpEngine(scheduler, 4);
             ExecutorService workers = Executors.newFixedThreadPool(requests)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PhpEngine.Result>> futures = new ArrayList<>();
            for (Path script : scripts) {
                futures.add(workers.submit(() -> {
                    start.await();
                    return run(engine, script);
                }));
            }
            start.countDown();
            for (int i = 0; i < requests; i++) {
                PhpEngine.Result result = futures.get(i).get(30, TimeUnit.SECONDS);
                char marker = (char) ('A' + i);
                assertEquals(200, result.status(), result.bodyText() + result.errors());
                assertEquals(String.valueOf(marker).repeat(lines), result.bodyText(),
                        "output of request " + marker + " mixed with another request");
            }
            assertTrue(engine.contextsCreated() <= requests,
                    "pool of 4 built " + engine.contextsCreated() + " contexts");
        }
    }

    /** An open link and an open transaction must not survive into the next request. */
    @Test
    void mysqliLinkAndTransactionAreFresh() throws Exception {
        String host = System.getenv("BLITZ_MYSQL_HOST");
        Assumptions.assumeTrue(host != null && !host.isEmpty(), "needs BLITZ_MYSQL_HOST");
        String database = System.getenv().getOrDefault("BLITZ_MYSQL_DB", "blitz");
        Assumptions.assumeTrue("blitz".equals(database), "needs BLITZ_MYSQL_DB=blitz");

        Path first = write("mysqli-first.php", """
                <?php
                mysqli_report(MYSQLI_REPORT_OFF);
                $db = mysqli_init();
                $ok = @mysqli_real_connect($db, getenv('BLITZ_MYSQL_HOST'), getenv('BLITZ_MYSQL_USER') ?: 'root',
                    getenv('BLITZ_MYSQL_PASSWORD') !== false ? getenv('BLITZ_MYSQL_PASSWORD') : 'wp',
                    getenv('BLITZ_MYSQL_DB') ?: 'blitz', (int) (getenv('BLITZ_MYSQL_PORT') ?: 33307));
                if (!$ok) { echo 'connect-failed'; exit; }
                mysqli_query($db, 'DROP TABLE IF EXISTS blitz_ctx_reuse');
                mysqli_query($db, 'CREATE TABLE blitz_ctx_reuse (id INT PRIMARY KEY, v VARCHAR(16)) ENGINE=InnoDB');
                mysqli_query($db, 'START TRANSACTION');
                echo 'inserted=', mysqli_query($db, "INSERT INTO blitz_ctx_reuse VALUES (1, 'open')") ? 'ok' : 'failed', "\\n";
                echo 'left-open=yes', "\\n";
                """);
        Path second = write("mysqli-second.php", """
                <?php
                mysqli_report(MYSQLI_REPORT_OFF);
                $db = mysqli_init();
                $ok = @mysqli_real_connect($db, getenv('BLITZ_MYSQL_HOST'), getenv('BLITZ_MYSQL_USER') ?: 'root',
                    getenv('BLITZ_MYSQL_PASSWORD') !== false ? getenv('BLITZ_MYSQL_PASSWORD') : 'wp',
                    getenv('BLITZ_MYSQL_DB') ?: 'blitz', (int) (getenv('BLITZ_MYSQL_PORT') ?: 33307));
                if (!$ok) { echo 'connect-failed'; exit; }
                mysqli_query($db, 'SET SESSION innodb_lock_wait_timeout = 2');
                $r = mysqli_query($db, 'SELECT COUNT(*) FROM blitz_ctx_reuse');
                echo 'rows=', mysqli_fetch_row($r)[0], "\\n";
                $r = mysqli_query($db, 'DELETE FROM blitz_ctx_reuse WHERE id = 1');
                echo 'delete=', $r === false ? 'locked' : 'ok', "\\n";
                mysqli_query($db, 'DROP TABLE IF EXISTS blitz_ctx_reuse');
                echo 'done=yes', "\\n";
                """);

        try (Scheduler scheduler = new Scheduler(1, 10, 0);
             PhpEngine engine = new PhpEngine(scheduler, 1)) {
            PhpEngine.Result one = run(engine, first);
            assertEquals(200, one.status(), one.bodyText() + one.errors());
            assertEquals("inserted=ok\nleft-open=yes\n", one.bodyText());

            PhpEngine.Result two = run(engine, second);
            assertEquals(200, two.status(), two.bodyText() + two.errors());
            assertEquals("rows=0\ndelete=ok\ndone=yes\n", two.bodyText());
            assertEquals(1, engine.contextsCreated());
        }
    }
}
