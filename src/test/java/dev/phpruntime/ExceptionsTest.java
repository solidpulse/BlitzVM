package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests uncaught exception CLI/HTTP formatting (including chained {@code Next} sections),
 * non-catchable fatals and scheduler cancellations, {@code exit} skipping {@code finally},
 * {@code debug_print_backtrace()}, and concurrent {@code ContextPolicy.SHARED} exception safety.
 */
class ExceptionsTest {

    private static PhpEngine engine;

    @BeforeAll
    static void setUp() {
        engine = new PhpEngine(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void uncaughtUserExceptionAndChainedNextMatchesPhpCli(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("uncaught_chain.php"), """
                <?php
                class MyAppException extends InvalidArgumentException {}
                function stepTwo() {
                    $cause = new LogicException("bad state", 1);
                    throw new MyAppException("invalid input", 2, $cause);
                }
                function stepOne() {
                    stepTwo();
                }
                stepOne();
                """);

        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());

        String realPhp = runPhpCli(script);
        assertEquals(normalizePath(realPhp, dir), normalizePath(res.bodyText(), dir));
    }

    @Test
    void uncaughtEmptyMessageAndEngineErrorMatchPhpCli(@TempDir Path dir) throws Exception {
        Path emptyEx = Files.writeString(dir.resolve("empty_msg.php"), """
                <?php
                throw new RuntimeException("");
                """);
        PhpEngine.Result r1 = engine.execute(emptyEx, new PhpRequest());
        assertEquals(500, r1.status());
        assertEquals(normalizePath(runPhpCli(emptyEx), dir), normalizePath(r1.bodyText(), dir));

        Path engErr = Files.writeString(dir.resolve("uncaught_err.php"), """
                <?php
                function boom() {
                    return intdiv(10, 0);
                }
                boom();
                """);
        PhpEngine.Result r2 = engine.execute(engErr, new PhpRequest());
        assertEquals(500, r2.status());
        assertEquals(normalizePath(runPhpCli(engErr), dir), normalizePath(r2.bodyText(), dir));
    }

    @Test
    void debugPrintBacktraceMatchesPhpCli(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("dpb.php"), """
                <?php
                class Worker {
                    public function start($x, $label) {
                        self::nextStep($x, $label, [1, 2]);
                    }
                    public static function nextStep($n, $s, $arr) {
                        leafPrint($n, $s, $arr, true, null);
                    }
                }
                function leafPrint($a, $b, $c, $d, $e) {
                    debug_print_backtrace();
                    echo '---', PHP_EOL;
                    debug_print_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS, 2);
                }
                (new Worker())->start(42, "secret");
                """);

        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals(normalizePath(runPhpCli(script), dir), normalizePath(res.bodyText(), dir));
    }

    @Test
    void exitInsideTryDoesNotRunFinally(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("exit_finally.php"), """
                <?php
                try {
                    echo 'before-exit', PHP_EOL;
                    exit('bye' . PHP_EOL);
                } finally {
                    echo 'MUST-NOT-RUN', PHP_EOL;
                }
                """);
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals("before-exit" + (char) 10 + "bye" + (char) 10, res.bodyText());
    }

    @Test
    void nonCatchableFatalsAreNotCaughtByThrowable(@TempDir Path dir) throws Exception {
        Path redeclareBuiltin = Files.writeString(dir.resolve("redecl_ex.php"), """
                <?php
                try {
                    if (true) {
                        class Exception {}
                    }
                } catch (Throwable $t) {
                    echo "CAUGHT
";
                }
                """);
        PhpEngine.Result r1 = engine.execute(redeclareBuiltin, new PhpRequest());
        assertEquals(500, r1.status());
        assertFalse(r1.bodyText().contains("CAUGHT"));
        assertTrue(r1.bodyText().contains("Cannot declare class Exception, because the name is already in use"), r1.bodyText());

        Path implThrowable = Files.writeString(dir.resolve("impl_throwable.php"), """
                <?php
                try {
                    if (true) {
                        class DirectThrowable implements Throwable {}
                    }
                } catch (Throwable $t) {
                    echo "CAUGHT
";
                }
                """);
        PhpEngine.Result r2 = engine.execute(implThrowable, new PhpRequest());
        assertEquals(500, r2.status());
        assertFalse(r2.bodyText().contains("CAUGHT"));
        assertTrue(r2.bodyText().contains("cannot implement interface Throwable, extend Exception or Error instead"), r2.bodyText());

        Path overrideFinal = Files.writeString(dir.resolve("override_final.php"), """
                <?php
                class BadOverride extends Exception {
                    public function getMessage() { return "x"; }
                }
                """);
        PhpEngine.Result r3 = engine.execute(overrideFinal, new PhpRequest());
        assertEquals(500, r3.status());
        assertTrue(r3.bodyText().contains("Cannot override final method Exception::getMessage()"), r3.bodyText());
    }

    @Test
    void schedulerTimeoutIsNotCaughtByTryCatchThrowable(@TempDir Path dir) throws Exception {
        Path hog = Files.writeString(dir.resolve("hog_try.php"), """
                <?php
                try {
                    while (true) {
                    }
                } catch (Throwable $t) {
                    echo "CAUGHT-TIMEOUT";
                } finally {
                    echo "FINALLY-ON-TIMEOUT";
                }
                """);
        try (Scheduler scheduler = new Scheduler(1, 10, 300); PhpEngine timedEngine = new PhpEngine(scheduler)) {
            PhpEngine.Result res = timedEngine.execute(hog, new PhpRequest());
            assertEquals(500, res.status());
            assertFalse(res.bodyText().contains("CAUGHT-TIMEOUT"), res.bodyText());
            assertFalse(res.bodyText().contains("FINALLY-ON-TIMEOUT"), res.bodyText());
            assertTrue(res.bodyText().contains("Maximum execution time of 0.3 seconds exceeded"), res.bodyText());
        }
    }

    @Test
    void concurrentSharedAstTryCatchIsolation(@TempDir Path dir) throws Exception {
        // Same shared AST executed concurrently across 8 threads, with polymorphic user and built-in
        // exceptions thrown and caught at the same try/catch site to stress shared CatchClause nodes.
        Path shared = Files.writeString(dir.resolve("concurrent_ex.php"), """
                <?php
                $mode = (int) ($_GET['mode'] ?? 0);
                if ($mode === 0) {
                    class ReqEx extends LogicException {}
                } else {
                    class ReqEx extends RuntimeException {}
                }
                function work(int $i, int $mode): string {
                    try {
                        if ($i % 3 === 0) {
                            throw new ReqEx("u:$mode:$i", $i);
                        }
                        if ($i % 3 === 1) {
                            intdiv(1, 0);
                        }
                        throw new InvalidArgumentException("inv:$i");
                    } catch (DivisionByZeroError $d) {
                        return "dbz";
                    } catch (InvalidArgumentException $ia) {
                        return $ia->getMessage();
                    } catch (LogicException $le) {
                        return "L:" . $le->getMessage();
                    } catch (RuntimeException $re) {
                        return "R:" . $re->getMessage();
                    } finally {
                        $i++;
                    }
                }
                $acc = [];
                for ($k = 0; $k < 30; $k++) {
                    $acc[] = work($k, $mode);
                }
                echo implode(",", array_slice($acc, 0, 4)), ":", count($acc);
                """);

        // Warm both mode=0 and mode=1 once so pre-existing DispatchCallNode cachedTarget race
        // (fixed on branch shared-policy-fixes) does not mask concurrent TryCatchNode testing.
        for (String m : List.of("0", "1")) {
            PhpRequest warm = new PhpRequest();
            warm.get.put("mode", m);
            assertEquals(200, engine.execute(shared, warm).status());
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < 64; t++) {
                final int mode = t % 2;
                tasks.add(() -> {
                    PhpRequest req = new PhpRequest();
                    req.get.put("mode", Integer.toString(mode));
                    PhpEngine.Result r = engine.execute(shared, req);
                    assertEquals(200, r.status(), r.bodyText());
                    String prefix = mode == 0 ? "L:u:0:0" : "R:u:1:0";
                    assertEquals(prefix + ",dbz,inv:2," + (mode == 0 ? "L:u:0:3" : "R:u:1:3") + ":30", r.bodyText());
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String runPhpCli(Path script) throws Exception {
        Process p = new ProcessBuilder("/usr/bin/php", script.toString())
                .redirectErrorStream(true)
                .start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String normalizePath(String s, Path dir) {
        String real;
        try {
            real = dir.toRealPath().toString();
        } catch (Exception e) {
            real = dir.toAbsolutePath().normalize().toString();
        }
        return s.replace(real + "/", "<dir>/")
                .replace(dir.toAbsolutePath().normalize() + "/", "<dir>/")
                .stripLeading();
    }
}
