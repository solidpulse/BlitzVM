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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrency, isolation, and fatal/notice error-path tests for Task 05. */
class ReferencesAndSyntaxTest {

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
    void globalsAndNamedArgCompileTimeErrors(@TempDir Path dir) throws Exception {
        Path assignGlobals = Files.writeString(dir.resolve("assign_globals.php"), """
                <?php
                $GLOBALS = [];
                """);
        PhpEngine.Result r1 = engine.execute(assignGlobals, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("$GLOBALS can only be modified"), r1.bodyText());

        Path refGlobals = Files.writeString(dir.resolve("ref_globals.php"), """
                <?php
                $r = &$GLOBALS;
                """);
        PhpEngine.Result r2 = engine.execute(refGlobals, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("Cannot acquire reference to $GLOBALS"), r2.bodyText());

        Path posAfterNamed = Files.writeString(dir.resolve("pos_after_named.php"), """
                <?php
                function f($a, $b) {}
                f(a: 1, 2);
                """);
        PhpEngine.Result r3 = engine.execute(posAfterNamed, new PhpRequest());
        assertEquals(500, r3.status());
        assertTrue(r3.bodyText().contains("Cannot use positional argument after named argument"), r3.bodyText());

        Path fnResultByRef = Files.writeString(dir.resolve("fn_res_ref.php"), """
                <?php
                function produce() { return 10; }
                function bump(&$x) { $x += 5; return $x; }
                set_error_handler(function ($errno, $errstr) {
                    echo "notice:$errno:$errstr|";
                    return true;
                });
                echo bump(produce());
                """);
        PhpEngine.Result r4 = engine.execute(fnResultByRef, new PhpRequest());
        assertEquals(200, r4.status());
        assertEquals("notice:8:Only variables should be passed by reference|15", r4.bodyText());
    }

    @Test
    void globalsByIdIsolatedAcrossConcurrentRequests(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("globals_iso.php"), """
                <?php
                $seed = (int) ($_GET['seed'] ?? 0);
                $top = $seed * 10;
                function mutateGlobal(int $add): int {
                    global $top;
                    $top += $add;
                    $GLOBALS['dyn_' . $add] = $top;
                    return $GLOBALS['top'];
                }
                $after = mutateGlobal(3);
                echo "$seed:$top:$after:" . $GLOBALS['dyn_3'];
                """);

        for (int i = 1; i <= 5; i++) {
            PhpRequest req = new PhpRequest();
            req.get.put("seed", String.valueOf(i));
            PhpEngine.Result res = engine.execute(script, req);
            assertEquals(200, res.status());
            int expected = i * 10 + 3;
            assertEquals(i + ":" + expected + ":" + expected + ":" + expected, res.bodyText());
        }
    }

    @Test
    void concurrentSharedByRefCallSiteWithMixedArgumentKinds(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("concurrent_refs.php"), """
                <?php
                class CounterBoxA {
                    public function __construct(public int $v, public readonly string $tag = 'A') {}
                }
                class CounterBoxB {
                    public function __construct(public int $v, public readonly string $tag = 'B') {}
                }

                function stepRef(&$target, int $delta = 1): int {
                    $target += $delta;
                    return $target;
                }

                function applySharedCallSite(int $mode, int $base): string {
                    $localPlain = $base;
                    $arr = ['slot' => $base * 2];
                    $box = ($mode % 2 === 0) ? new CounterBoxA($base * 3) : new CounterBoxB($base * 3);
                    $dynFn = 'stepRef';

                    // Shared call sites hit concurrently across requests with local, array element, and property l-values
                    for ($i = 0; $i < 3; $i++) {
                        if ($i === 0) {
                            stepRef($localPlain, 2);
                            $dynFn($arr['slot'], 3);
                        } elseif ($i === 1) {
                            stepRef($arr['slot'], 4);
                            $dynFn($box->v, 5);
                        } else {
                            stepRef($box->v, 6);
                            $dynFn($localPlain, 7);
                        }
                    }

                    [$p, $q] = [$localPlain, $arr['slot']];
                    $spreadArgs = [&$p, 10];
                    stepRef(...$spreadArgs);

                    return "{$box->tag}:$p:$q:{$box->v}";
                }

                $m = (int) ($_GET['mode'] ?? 0);
                $b = (int) ($_GET['base'] ?? 1);
                $GLOBALS['req_res'] = applySharedCallSite($m, $b);
                echo $GLOBALS['req_res'];
                """);

        int threads = 16;
        int runsPerThread = 20;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int r = 0; r < runsPerThread; r++) {
                        int mode = (threadIdx + r) & 1;
                        int base = threadIdx * 10 + r + 1;
                        PhpRequest req = new PhpRequest();
                        req.get.put("mode", String.valueOf(mode));
                        req.get.put("base", String.valueOf(base));
                        PhpEngine.Result res = engine.execute(script, req);
                        String expectedTag = (mode % 2 == 0) ? "A" : "B";
                        int expectedP = base + 2 + 7 + 10;
                        int expectedQ = base * 2 + 3 + 4;
                        int expectedV = base * 3 + 5 + 6;
                        String expected = expectedTag + ":" + expectedP + ":" + expectedQ + ":" + expectedV;
                        if (res.status() != 200 || !expected.equals(res.bodyText())) {
                            return "thread=" + threadIdx + " run=" + r + " status=" + res.status()
                                    + " expected=" + expected + " got=" + res.bodyText() + " err=" + res.errors();
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
