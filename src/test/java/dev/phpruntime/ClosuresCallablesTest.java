package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error, isolation, and shared-engine concurrency tests for Task 03. */
class ClosuresCallablesTest {

    private PhpEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PhpEngine(null);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void nonCallableAndClosureErrors(@TempDir Path dir) throws Exception {
        Path nonCallable = Files.writeString(dir.resolve("noncallable.php"), """
                <?php
                $f = 42;
                $f(1);
                """);
        PhpEngine.Result r1 = engine.execute(nonCallable, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("Value not of type callable"), r1.bodyText());

        Path badNewClosure = Files.writeString(dir.resolve("newclosure.php"), """
                <?php
                new Closure();
                """);
        PhpEngine.Result r2 = engine.execute(badNewClosure, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("Instantiation of Closure is not allowed"), r2.bodyText());

        Path badExtendClosure = Files.writeString(dir.resolve("extclosure.php"), """
                <?php
                class MyC extends Closure {}
                """);
        PhpEngine.Result r3 = engine.execute(badExtendClosure, new PhpRequest());
        assertEquals(500, r3.status());
        assertTrue(r3.bodyText().contains("cannot extend final class Closure"), r3.bodyText());

        Path globalFuncArgs = Files.writeString(dir.resolve("globalfuncargs.php"), """
                <?php
                func_get_args();
                """);
        PhpEngine.Result r4 = engine.execute(globalFuncArgs, new PhpRequest());
        assertEquals(500, r4.status());
        assertTrue(r4.bodyText().contains("Cannot be called from the global scope"), r4.bodyText());

        Path badCallUserFunc = Files.writeString(dir.resolve("badcuf.php"), """
                <?php
                call_user_func('non_existent_fn');
                """);
        PhpEngine.Result r5 = engine.execute(badCallUserFunc, new PhpRequest());
        assertEquals(500, r5.status());
        assertTrue(r5.bodyText().contains("Value not of type callable"), r5.bodyText());

        Path errorFallthrough = Files.writeString(dir.resolve("errfall.php"), """
                <?php
                set_error_handler(function ($errno, $errstr) {
                    echo "handled:$errno;";
                    return false;
                });
                define('E_DUP', 1);
                define('E_DUP', 2);
                """);
        PhpEngine.Result r6 = engine.execute(errorFallthrough, new PhpRequest());
        assertEquals(200, r6.status());
        assertEquals("handled:2;", r6.bodyText());
    }

    @Test
    void staticLocalsAndHandlersIsolatedAcrossRequests(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("iso.php"), """
                <?php
                function reqBump() {
                    static $n = 0;
                    return ++$n;
                }
                register_shutdown_function(function () { echo '|shut'; });
                echo reqBump(), ',', reqBump();
                """);
        PhpEngine.Result a = engine.execute(script, new PhpRequest());
        PhpEngine.Result b = engine.execute(script, new PhpRequest());
        assertEquals(200, a.status());
        assertEquals(200, b.status());
        assertEquals("1,2|shut", a.bodyText());
        assertEquals("1,2|shut", b.bodyText());
    }

    @Test
    void concurrentSharedEngineRequests(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("DynWorker.php"), """
                <?php
                class DynWorker {
                    public string $prefix;
                    public function __construct(string $p) { $this->prefix = $p; }
                    public function fmt(int $n): string { return $this->prefix . ':' . $n; }
                    public static function tag(int $n): string {
                        static $calls = 0;
                        $calls++;
                        return 'w#' . $n . ':' . $calls;
                    }
                }
                """);
        Path script = Files.writeString(dir.resolve("concurrent.php"), """
                <?php
                function addOne(int $x): int {
                    static $hits = 0;
                    $hits++;
                    return $x + $hits;
                }
                spl_autoload_register(function ($cls) {
                    $file = __DIR__ . '/' . $cls . '.php';
                    if (file_exists($file)) {
                        require_once $file;
                    }
                });
                $strCb = 'addOne';
                $clos = function (int $k) use (&$acc) {
                    $acc = ($acc ?? 0) + $k;
                    return $acc;
                };
                $w = new DynWorker('ok');
                $objCb = [$w, 'fmt'];
                $staticCb = 'DynWorker::tag';
                $nums = [1, 2, 3];
                $mapped = array_map(fn($x) => $strCb($x), $nums);
                $sum = $clos(10) + call_user_func($clos, 20);
                echo implode(',', $mapped), '|', $sum, '|', $objCb(9), '|',
                     DynWorker::tag(5), ',', $staticCb(6), ',', DynWorker::tag(7);
                """);

        String expected = "2,4,6|40|ok:9|w#5:1,w#6:2,w#7:3";
        int threads = 16;
        int runsPerThread = 20;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int r = 0; r < runsPerThread; r++) {
                        PhpEngine.Result res = engine.execute(script, new PhpRequest());
                        if (res.status() != 200 || !expected.equals(res.bodyText())) {
                            return "status=" + res.status() + " body=" + res.bodyText();
                        }
                    }
                    return "OK";
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<String> f : futures) {
                assertEquals("OK", f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
