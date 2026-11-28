package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FrankenPHP-style worker mode: one script booted once per long-lived worker, with request
 * state cleared between requests and everything the script keeps left alone.
 */
class WorkerModeTest {

    @TempDir
    Path dir;

    private Scheduler scheduler;
    private PhpEngine engine;
    private WorkerPool workers;
    private PhpDispatcher dispatcher;

    private void start(String workerCode) throws Exception {
        start(workerCode, 2, 1, 0, Map.of());
    }

    private void start(String workerCode, int cpus, int workerCount, long maxExecMillis,
                       Map<String, String> environment) throws Exception {
        Files.writeString(dir.resolve("index.php"), "<?php\n");
        Files.writeString(dir.resolve("worker.php"), workerCode);
        scheduler = new Scheduler(cpus, 10, maxExecMillis);
        engine = new PhpEngine(scheduler, 1);
        workers = new WorkerPool(engine, scheduler, dir.resolve("worker.php"), dir, workerCount, 80,
                environment);
        dispatcher = new PhpDispatcher(dir, engine, null, workers);
        workers.start();
        assertTrue(workers.awaitReady(WorkerPool.STARTUP_TIMEOUT_MILLIS), "workers did not become ready");
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
        if (workers != null) {
            workers.close();
        }
        if (engine != null) {
            engine.close();
        }
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private PhpDispatcher.Response send(String method, String path, String query,
                                        Map<String, String> headers, String body) {
        return dispatcher.handle(new PhpDispatcher.Request(method, path, query, "HTTP/1.1", headers,
                PhpStrings.bytes(body), "127.0.0.1", "localhost", 80));
    }

    private PhpDispatcher.Response get(String path) {
        return get(path, Map.of());
    }

    private PhpDispatcher.Response get(String path, Map<String, String> headers) {
        int q = path.indexOf('?');
        return send("GET", q < 0 ? path : path.substring(0, q), q < 0 ? null : path.substring(q + 1),
                headers, "");
    }

    private static String text(PhpDispatcher.Response response) {
        return PhpStrings.toJava(PhpStrings.of(response.body()));
    }

    private static String header(PhpDispatcher.Response response, String name) {
        for (String[] h : response.headers()) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    private static int headers(PhpDispatcher.Response response, String name) {
        int n = 0;
        for (String[] h : response.headers()) {
            if (h[0].equalsIgnoreCase(name)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void serverAndEnvHaveFrankenPhpOrderAndValuesAcrossRequests() throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("T82_ENV", "env-value");
        environment.put("T82_HEADERLIKE", "env-value-2");
        start("""
                <?php
                $n = 0;
                $handler = static function () use (&$n): void {
                    $n++;
                    $s = $_SERVER;
                    unset($s['REQUEST_TIME'], $s['REQUEST_TIME_FLOAT']);
                    foreach ($s as $k => $v) {
                        echo 'S ', $k, '=', $v, "\\n";
                    }
                    foreach ($_ENV as $k => $v) {
                        echo 'E ', $k, '=', $v, "\\n";
                    }
                    echo 'TIME ', is_numeric($_SERVER['REQUEST_TIME']), ' ',
                        is_numeric($_SERVER['REQUEST_TIME_FLOAT']), "\\n";
                    if ($n === 1) {
                        $_SERVER['T82_LEAK'] = 's';
                        $_ENV['T82_LEAK'] = 'e';
                        $_GET['T82_LEAK'] = 'g';
                        $_POST['T82_LEAK'] = 'p';
                        $_COOKIE['T82_LEAK'] = 'c';
                        $_REQUEST['T82_LEAK'] = 'r';
                        $_FILES['T82_LEAK'] = 'f';
                        echo 'MOD ', json_encode([$_SERVER['T82_LEAK'], $_ENV['T82_LEAK'],
                            $_GET['T82_LEAK'], $_POST['T82_LEAK'], $_COOKIE['T82_LEAK'],
                            $_REQUEST['T82_LEAK'], $_FILES['T82_LEAK']]), "\\n";
                    } else {
                        echo 'CLEAN ', json_encode([
                            isset($_SERVER['T82_LEAK']), isset($_ENV['T82_LEAK']),
                            isset($_GET['T82_LEAK']), isset($_POST['T82_LEAK']),
                            isset($_COOKIE['T82_LEAK']), isset($_REQUEST['T82_LEAK']),
                            isset($_FILES['T82_LEAK'])]), "\\n";
                    }
                };
                while (frankenphp_handle_request($handler)) {
                }
                """, 2, 1, 0, environment);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "example.test");
        headers.put("User-Agent", "t82");
        headers.put("X-Probe", "probe");
        headers.put("Cookie", "c=d");
        headers.put("Content-Type", "application/x-www-form-urlencoded");

        List<String> expected = new ArrayList<>();
        for (Map.Entry<String, String> e : workers.environment().entrySet()) {
            expected.add("S " + e.getKey() + "=" + e.getValue());
        }
        expected.add("S REQUEST_METHOD=POST");
        expected.add("S REQUEST_URI=/first?a=1");
        expected.add("S QUERY_STRING=a=1");
        expected.add("S SCRIPT_NAME=/worker.php");
        expected.add("S PHP_SELF=/worker.php");
        expected.add("S GATEWAY_INTERFACE=CGI/1.1");
        expected.add("S REQUEST_SCHEME=http");
        expected.add("S SCRIPT_FILENAME=" + dir.resolve("worker.php"));
        expected.add("S DOCUMENT_ROOT=" + dir);
        expected.add("S SERVER_PROTOCOL=HTTP/1.1");
        expected.add("S SERVER_SOFTWARE=php-truffle");
        expected.add("S SERVER_NAME=localhost");
        expected.add("S SERVER_PORT=80");
        expected.add("S REMOTE_ADDR=127.0.0.1");
        expected.add("S HTTP_HOST=example.test");
        expected.add("S HTTP_USER_AGENT=t82");
        expected.add("S HTTP_X_PROBE=probe");
        expected.add("S HTTP_COOKIE=c=d");
        expected.add("S CONTENT_TYPE=application/x-www-form-urlencoded");
        expected.add("S PATH_INFO=");
        expected.add("S DOCUMENT_URI=");
        expected.add("S HTTPS=");
        expected.add("S AUTH_TYPE=");
        expected.add("S REMOTE_HOST=127.0.0.1");
        expected.add("S REMOTE_IDENT=");
        expected.add("S CONTENT_LENGTH=");
        for (Map.Entry<String, String> e : workers.environment().entrySet()) {
            expected.add("E " + e.getKey() + "=" + e.getValue());
        }
        expected.add("TIME 1 1");

        String[] first = text(send("POST", "/first", "a=1", headers, "k=v")).split("\n");
        String[] second = text(send("POST", "/first", "a=1", headers, "k=v")).split("\n");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), first[i], "first request line " + i);
            assertEquals(expected.get(i), second[i], "second request line " + i);
        }
        assertEquals("MOD [\"s\",\"e\",\"g\",\"p\",\"c\",\"r\",\"f\"]",
                first[expected.size()], String.join("\n", first));
        assertEquals("CLEAN [false,false,false,false,false,false,false]",
                second[expected.size()], String.join("\n", second));
        assertEquals(expected.size() + 1, first.length, String.join("\n", first));
        assertEquals(expected.size() + 1, second.length, String.join("\n", second));
    }

    @Test
    void lazySuperglobalsAreBuiltOnFirstAccessThroughEachPath() throws Exception {
        start("""
                <?php
                $handler = static function (): void {
                    $p = $_GET['p'] ?? '';
                    switch ($p) {
                        case 'globals':
                            echo $GLOBALS['_SERVER']['HTTP_X_PROBE'];
                            break;
                        case 'globalsDynamic':
                            $name = '_SERVER';
                            echo $GLOBALS[$name]['HTTP_X_PROBE'];
                            break;
                        case 'globalsEnv':
                            echo $GLOBALS['_ENV']['T82_ENV'] ?? 'missing';
                            break;
                        case 'extract':
                            extract($GLOBALS);
                            echo $_SERVER['HTTP_X_PROBE'];
                            break;
                        case 'compact':
                            // PHP's compact() reads the current symbol table, not the globals:
                            // inside a function it does not see a superglobal.
                            $c = compact('_SERVER');
                            echo isset($c['_SERVER']) ? 'found' : 'missing';
                            break;
                        case 'isset':
                            echo isset($_SERVER) ? 'set' : 'unset';
                            break;
                        case 'foreach':
                            foreach ($_SERVER as $k => $v) {
                                if ($k === 'HTTP_X_PROBE') {
                                    echo $v;
                                    break;
                                }
                            }
                            break;
                        case 'byref':
                            $s = &$_SERVER;
                            echo $s['HTTP_X_PROBE'];
                            $s['T82_REF'] = 'yes';
                            echo $_SERVER['T82_REF'];
                            break;
                        case 'checkref':
                            echo isset($_SERVER['T82_REF']) ? 'leak' : 'clean';
                            break;
                        case 'post':
                            echo $_POST['k'] ?? 'missing';
                            break;
                        case 'cookie':
                            echo $_COOKIE['c'] ?? 'missing';
                            break;
                        case 'request':
                            echo ($_REQUEST['k'] ?? 'missing'), '/', ($_REQUEST['c'] ?? 'missing');
                            break;
                        case 'files':
                            echo is_array($_FILES) ? 'array' : 'not-array';
                            break;
                        case 'envArray':
                            echo is_array($_ENV) && ($_ENV['T82_ENV'] ?? '') === 'env-value'
                                ? 'env' : 'bad';
                            break;
                        case 'foreachRef':
                            foreach ($_ENV as $k => &$v) {
                                if ($k === 'T82_ENV') {
                                    $v = 'changed';
                                }
                            }
                            unset($v);
                            echo $_ENV['T82_ENV'] ?? 'missing';
                            break;
                        case 'checkenv':
                            echo $_ENV['T82_ENV'] ?? 'missing';
                            break;
                        case 'getenv':
                            echo getenv('PATH') !== false ? 'getenv-ok' : 'getenv-missing';
                            break;
                    }
                };
                while (frankenphp_handle_request($handler)) {
                }
                """, 2, 1, 0, Map.of("T82_ENV", "env-value"));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Probe", "probe");
        headers.put("Cookie", "c=d");
        headers.put("Content-Type", "application/x-www-form-urlencoded");

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("globals", "probe");
        expected.put("globalsDynamic", "probe");
        expected.put("globalsEnv", "env-value");
        expected.put("extract", "probe");
        expected.put("compact", "missing");
        expected.put("isset", "set");
        expected.put("foreach", "probe");
        expected.put("byref", "probeyes");
        expected.put("checkref", "clean");
        expected.put("post", "v");
        expected.put("cookie", "d");
        expected.put("request", "v/d");
        expected.put("files", "array");
        expected.put("envArray", "env");
        expected.put("foreachRef", "changed");
        expected.put("checkenv", "env-value");
        expected.put("getenv", "getenv-ok");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            PhpDispatcher.Response response = send("POST", "/lazy", "p=" + e.getKey(), headers, "k=v");
            assertEquals(e.getValue(), text(response), e.getKey() + " via " + headers);
        }
    }

    @Test
    void classicModeSuperglobalsAreStillBuiltEagerly() throws Exception {
        Path plain = dir.resolve("plain.php");
        Files.writeString(plain, """
                <?php
                echo 'S ', implode(',', array_keys($_SERVER)), "\\n";
                echo 'M ', $_SERVER['REQUEST_METHOD'], ' ', $_SERVER['HTTP_X_PLAIN'] ?? 'missing', "\\n";
                echo 'E ', $_ENV['T82_ENV'] ?? 'missing', "\\n";
                echo 'G ', json_encode($_GET), "\\n";
                """);
        try (Scheduler plainScheduler = new Scheduler(1, 10, 0);
             PhpEngine plainEngine = new PhpEngine(plainScheduler, 1)) {
            PhpRequest request = new PhpRequest();
            request.http = true;
            request.method = "POST";
            request.uri = "/plain?x=1";
            request.queryString = "x=1";
            request.scriptFilename = plain.toString();
            request.server.put("REQUEST_METHOD", "POST");
            request.server.put("QUERY_STRING", "x=1");
            request.server.put("HTTP_X_PLAIN", "yes");
            request.environment.put("T82_ENV", "env-value");
            PhpEngine.Result result = plainEngine.execute(plain, request);
            assertEquals("""
                    S REQUEST_METHOD,QUERY_STRING,HTTP_X_PLAIN,SCRIPT_FILENAME
                    M POST yes
                    E env-value
                    G {"x":"1"}
                    """, result.bodyText(), result.errors());
        }
    }

    @Test
    void requestStateIsClearedBetweenRequests() throws Exception {
        start("""
                <?php
                $handler = static function (): void {
                    echo 'ob=', ob_get_level(),
                        ' get=', json_encode($_GET),
                        ' post=', json_encode($_POST),
                        ' input=', file_get_contents('php://input'),
                        ' cookie=', json_encode($_COOKIE),
                        ' custom=', json_encode($_SERVER['HTTP_X_CUSTOM'] ?? null),
                        ' status=', http_response_code(),
                        ' headers=', json_encode(headers_list()), "\\n";
                    header('X-Request: yes');
                    if (($_GET['first'] ?? '') === '1') {
                        http_response_code(202);
                        setcookie('one', '1');
                        ob_start();
                        echo "buffered\\n";
                    }
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);

        PhpDispatcher.Response one = send("POST", "/first", "first=1&a=1",
                Map.of("Cookie", "c=d", "X-Custom", "x",
                        "Content-Type", "application/x-www-form-urlencoded"), "k=v");
        assertEquals(202, one.status(), text(one));
        assertEquals("yes", header(one, "X-Request"));
        assertNotNull(header(one, "Set-Cookie"));
        assertTrue(text(one).contains("ob=0 get={\"first\":\"1\",\"a\":\"1\"} post={\"k\":\"v\"}"),
                text(one));
        assertTrue(text(one).contains("cookie={\"c\":\"d\"} custom=\"x\" status=200 headers=[]"), text(one));
        assertTrue(text(one).endsWith("buffered\n"), text(one));

        PhpDispatcher.Response two = get("/second?b=2");
        assertEquals(200, two.status(), text(two));
        assertEquals("yes", header(two, "X-Request"));
        assertNull(header(two, "Set-Cookie"), "a cookie set by the first request leaked");
        assertEquals(0, headers(two, "Set-Cookie"), "a cookie set by the first request leaked");
        assertEquals(1, headers(two, "X-Request"));
        assertTrue(text(two).contains("ob=0 get={\"b\":\"2\"} post=[] input= cookie=[] custom=null "
                + "status=200 headers=[]"), text(two));
        assertFalse(text(two).contains("buffered"), text(two));
    }

    @Test
    void whatTheScriptKeepsSurvives() throws Exception {
        start("""
                <?php
                $count = 0;
                $bump = static function () use (&$count): int { return ++$count; };
                class Kept { public static int $n = 0; public static function bump(): int { return ++self::$n; } }
                function keptStatic(): int { static $n = 0; return ++$n; }
                $handler = static function () use ($bump): void {
                    echo 'global=', $bump(), ' class=', Kept::bump(), ' static=', keptStatic(), "\\n";
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);

        assertEquals("global=1 class=1 static=1\n", text(get("/one")));
        assertEquals("global=2 class=2 static=2\n", text(get("/two")));
        assertEquals("global=3 class=3 static=3\n", text(get("/three")));
    }

    @Test
    void anUncaughtExceptionAnswersAndKeepsTheWorker() throws Exception {
        start("""
                <?php
                $n = 0;
                $handler = static function () use (&$n): void {
                    $n++;
                    echo "before n=$n\\n";
                    if (($_GET['boom'] ?? '') === '1') {
                        throw new RuntimeException('boom');
                    }
                    echo "after n=$n\\n";
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);

        PhpDispatcher.Response failed = get("/x?boom=1");
        assertEquals(200, failed.status(), text(failed));
        assertTrue(text(failed).contains("before n=1"), text(failed));
        assertTrue(text(failed).contains("Uncaught RuntimeException: boom"), text(failed));

        PhpDispatcher.Response next = get("/x");
        assertEquals(200, next.status(), text(next));
        assertEquals("before n=2\nafter n=2\n", text(next));
    }

    @Test
    void exitEndsTheWorkerScriptAndARestartBootsAgain() throws Exception {
        start(bootCounterScript("""
                echo "boot=$boot n=$n\\n";
                if (($_GET['exit'] ?? '') === '1') {
                    echo "exiting\\n";
                    exit(3);
                }
                """), 2, 1, 0, Map.of());

        assertEquals("boot=1 n=1\n", text(get("/one")));
        PhpDispatcher.Response exit = get("/two?exit=1");
        assertTrue(text(exit).contains("exiting"), text(exit));

        PhpDispatcher.Response after = get("/three");
        assertEquals(200, after.status(), text(after));
        assertEquals("boot=2 n=1\n", text(after));
    }

    @Test
    void aFatalErrorRestartsTheWorker() throws Exception {
        start(bootCounterScript("""
                echo "boot=$boot n=$n\\n";
                if (($_GET['fatal'] ?? '') === '1') {
                    eval('function strlen() {}');
                }
                """), 2, 1, 0, Map.of());

        PhpDispatcher.Response fatal = get("/one?fatal=1");
        assertTrue(text(fatal).contains("Cannot redeclare function strlen()"), text(fatal));

        PhpDispatcher.Response after = get("/two");
        assertEquals(200, after.status(), text(after));
        assertEquals("boot=2 n=1\n", text(after));
    }

    @Test
    void aTimedOutRequestAnswersAndRestartsTheWorker() throws Exception {
        start(bootCounterScript("""
                echo "boot=$boot n=$n\\n";
                if (($_GET['spin'] ?? '') === '1') {
                    while (true) {
                    }
                }
                """), 1, 1, 400, Map.of());

        PhpDispatcher.Response timedOut = get("/one?spin=1");
        assertEquals(500, timedOut.status(), text(timedOut));
        assertTrue(text(timedOut).contains("Maximum execution time"), text(timedOut));

        PhpDispatcher.Response after = get("/two");
        assertEquals(200, after.status(), text(after));
        assertEquals("boot=2 n=1\n", text(after));
    }

    @Test
    void maxRequestsRestartsTheWorker() throws Exception {
        start("""
                <?php
                $bootFile = __DIR__ . '/boots.txt';
$boot = is_file($bootFile) ? (int) file_get_contents($bootFile) + 1 : 1;
                file_put_contents($bootFile, (string) $boot);
                $max = (int) ($_SERVER['MAX_REQUESTS'] ?? 0);
                $bootMax = $_SERVER['MAX_REQUESTS'] ?? '<unset>';
                $bootFranken = $_SERVER['FRANKENPHP_WORKER'] ?? '<unset>';
                $n = 0;
                $handler = static function () use (&$n, $boot, $bootMax, $bootFranken): void {
                    $n++;
                    echo "boot=$boot bootmax=$bootMax bootfranken=$bootFranken requestfranken=",
                        $_SERVER['FRANKENPHP_WORKER'] ?? '<unset>', " n=$n\\n";
                };
                for ($i = 0; $max === 0 || $i < $max; $i++) {
                    if (! frankenphp_handle_request($handler)) {
                        break;
                    }
                }
                """, 2, 1, 0, Map.of("MAX_REQUESTS", "2"));

        String first = text(get("/one"));
        assertEquals("boot=1 bootmax=2 bootfranken=1 requestfranken=<unset> n=1\n", first);
        assertEquals("boot=1 bootmax=2 bootfranken=1 requestfranken=<unset> n=2\n", text(get("/two")));
        String third = text(get("/three"));
        assertTrue(third.startsWith("boot=2 bootmax=2"), third);
        assertTrue(third.endsWith("n=1\n"), third);
    }

    @Test
    void aBusyRequestIsPreemptedSoAnotherWorkerCanAnswer() throws Exception {
        Files.writeString(dir.resolve("index.php"), "<?php\n");
        Files.writeString(dir.resolve("worker.php"), """
                <?php
                $handler = static function (): void {
                    if (($_GET['spin'] ?? '') === '1') {
                        $stop = __DIR__ . '/stop';
                        while (! file_exists($stop)) {
                        }
                        echo "spun\\n";
                        return;
                    }
                    if (($_GET['stop'] ?? '') === '1') {
                        file_put_contents(__DIR__ . '/stop', '1');
                        echo "stopped\\n";
                        return;
                    }
                    echo "ok\\n";
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);
        scheduler = new Scheduler(1, 10, 8_000);
        engine = new PhpEngine(scheduler, 1);
        workers = new WorkerPool(engine, scheduler, dir.resolve("worker.php"), dir, 2, 80, Map.of());
        dispatcher = new PhpDispatcher(dir, engine, null, workers);
        workers.start();
        assertTrue(workers.awaitReady(WorkerPool.STARTUP_TIMEOUT_MILLIS));
        Files.deleteIfExists(dir.resolve("stop"));

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<PhpDispatcher.Response> spinning = exec.submit(() -> get("/a?spin=1"));
            Thread.sleep(300);
            Future<PhpDispatcher.Response> stop = exec.submit(() -> get("/b?stop=1"));
            PhpDispatcher.Response stopped = stop.get(5, TimeUnit.SECONDS);
            assertEquals(200, stopped.status(), text(stopped));
            assertEquals("stopped\n", text(stopped));

            PhpDispatcher.Response spun = spinning.get(10, TimeUnit.SECONDS);
            assertEquals("spun\n", text(spun));
            assertTrue(scheduler.preemptions() > 0, "the busy request was never preempted");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void idleWorkersHoldNoSlot() throws Exception {
        start("""
                <?php
                $handler = static function (): void { echo "ok\\n"; };
                while (frankenphp_handle_request($handler)) {
                }
                """, 1, 2, 0, Map.of());

        Thread.sleep(200);
        assertEquals(0, scheduler.runningCount(), "an idle worker is holding an execution slot");

        assertEquals("ok\n", text(get("/one")));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scheduler.runningCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, scheduler.runningCount(), "a worker kept its slot after answering");
    }

    @Test
    void frankenphpFunctionsExistOnlyInWorkerMode() throws Exception {
        start("""
                <?php
                $handler = static function (): void {
                    echo 'exists=', var_export(function_exists('frankenphp_handle_request'), true),
                        ' finish=', var_export(function_exists('frankenphp_finish_request'), true), "\\n";
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);
        assertEquals("exists=true finish=true\n", text(get("/one")));

        Path plain = dir.resolve("plain.php");
        Files.writeString(plain, """
                <?php
                echo 'exists=', var_export(function_exists('frankenphp_handle_request'), true),
                    ' finish=', var_export(function_exists('frankenphp_finish_request'), true), "\\n";
                """);
        try (Scheduler plainScheduler = new Scheduler(1, 10, 0);
             PhpEngine plainEngine = new PhpEngine(plainScheduler, 1)) {
            PhpRequest request = new PhpRequest();
            request.http = true;
            PhpEngine.Result result = plainEngine.execute(plain, request);
            assertEquals("exists=false finish=false\n", result.bodyText(), result.errors());
        }
    }

    @Test
    void requestShutdownFunctionsRunAtTheEndOfTheirRequest() throws Exception {
        start("""
                <?php
                $n = 0;
                $handler = static function () use (&$n): void {
                    $n++;
                    echo "n=$n\\n";
                    register_shutdown_function(static function () use ($n): void {
                        echo "shutdown n=$n\\n";
                    });
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);

        assertEquals("n=1\nshutdown n=1\n", text(get("/one")));
        assertEquals("n=2\nshutdown n=2\n", text(get("/two")));
    }

    @Test
    void finishRequestSendsTheResponseAndKeepsRunning() throws Exception {
        Path after = dir.resolve("after.txt");
        start("""
                <?php
                $n = 0;
                $handler = static function () use (&$n): void {
                    $n++;
                    echo "before n=$n\\n";
                    frankenphp_finish_request();
                    echo "after n=$n\\n";
                    file_put_contents(__DIR__ . '/after.txt', "after n=$n\\n");
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);

        PhpDispatcher.Response one = get("/one");
        assertEquals(200, one.status(), text(one));
        assertEquals("before n=1\n", text(one));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(after) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("after n=1\n", Files.readString(after));
    }

    @Test
    void fastcgiFinishRequestIsTheSameAndReportsWhetherItSent() throws Exception {
        Path after = dir.resolve("after.txt");
        start("""
                <?php
                $handler = static function (): void {
                    echo "exists=" . var_export(function_exists('fastcgi_finish_request'), true) . "\n";
                    $first = fastcgi_finish_request();
                    $second = fastcgi_finish_request();
                    $third = frankenphp_finish_request();
                    echo "after\n";
                    file_put_contents(__DIR__ . '/after.txt', var_export([$first, $second, $third], true));
                };
                while (frankenphp_handle_request($handler)) {
                }
                """);

        PhpDispatcher.Response one = get("/one");
        assertEquals(200, one.status(), text(one));
        assertEquals("exists=true\n", text(one));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(after) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("array (\n  0 => true,\n  1 => false,\n  2 => false,\n)", Files.readString(after));
    }

    @Test
    void aWorkerScriptThatNeverReachesHandleRequestBacksOff() throws Exception {
        Files.writeString(dir.resolve("index.php"), "<?php\n");
        Files.writeString(dir.resolve("worker.php"), "<?php\n");
        scheduler = new Scheduler(1, 10, 0);
        engine = new PhpEngine(scheduler, 1);
        workers = new WorkerPool(engine, scheduler, dir.resolve("worker.php"), dir, 1, 80, Map.of());
        dispatcher = new PhpDispatcher(dir, engine, null, workers);
        workers.start();

        assertFalse(workers.awaitReady(800), "a script that never calls handle_request was reported ready");
        assertEquals(0, workers.readyCount());
        long attempts = workers.bootAttempts();
        assertTrue(attempts >= 2, "the worker did not retry");
        assertTrue(attempts < 12, "the worker retried without backing off: " + attempts + " boots in 800 ms");
    }

    /** A worker that counts its boots in a file and prints {@code boot} and the request number. */
    private static String bootCounterScript(String body) {
        return """
                <?php
                $bootFile = __DIR__ . '/boots.txt';
$boot = is_file($bootFile) ? (int) file_get_contents($bootFile) + 1 : 1;
                file_put_contents($bootFile, (string) $boot);
                $n = 0;
                $handler = static function () use (&$n, $boot): void {
                    $n++;
                """ + body.indent(4) + """
                };
                while (frankenphp_handle_request($handler)) {
                }
                """;
    }
}
