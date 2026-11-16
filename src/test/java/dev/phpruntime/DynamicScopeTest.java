package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicScopeTest {
    @Test
    void ordinaryFunctionsAndLiteralCompactKeepTypedFrames() {
        String code = """
                <?php
                function ordinary($n) { $x = 0; for ($i = 0; $i < $n; $i++) { $x += $i; } return $x; }
                function literal($a) { $b = 2; return compact('a', 'b'); }
                function nested() { $c = function () { extract(['x' => 1]); }; return 1; }
                function dynamic($a) { extract(['b' => 2]); return $a + $b; }
                __scope_shape_check();
                """;
        dev.phpruntime.builtins.Builtins.def("__scope_shape_check", 0, 0, (ctx, args) -> {
            for (String name : new String[]{"ordinary", "literal", "nested", "dynamic"}) {
                var function = ctx.lookupFunction(name);
                var functionRoot = function.callTarget().getRootNode();
                functionRoot.adoptChildren();
                boolean dynamic = function.name().equals("dynamic");
                assertEquals(dynamic, functionRoot.getFrameDescriptor().getInfo() instanceof dev.phpruntime.runtime.PhpScopeLayout);
                int[] counts = new int[2];
                countLocalNodes(functionRoot, counts);
                assertTrue(dynamic ? counts[1] > 0 && counts[0] == 0 : counts[1] == 0, function.name());
            }
            return dev.phpruntime.runtime.PhpNull.INSTANCE;
        });
        try (var context = org.graalvm.polyglot.Context.newBuilder("php").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("php", code);
        }
    }

    private static void countLocalNodes(com.oracle.truffle.api.nodes.Node node, int[] counts) {
        if (node instanceof dev.phpruntime.nodes.var.LocalVarNode) { counts[0]++; }
        if (node instanceof dev.phpruntime.nodes.var.ScopedVarNode) { counts[1]++; }
        for (var child : node.getChildren()) { countLocalNodes(child, counts); }
    }

    @Test
    void includesExtractAndClassScopeAreIsolatedAcrossSharedAsts(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("view.php"), """
                <?php
                $value += $delta;
                $made = $this->secret + $value;
                $closure = function () use (&$value) { return ++$value + $this->secret; };
                return self::TAG . ':' . static::TAG;
                """);
        Path script = Files.writeString(dir.resolve("main.php"), """
                <?php
                class A {
                    const TAG = 'A';
                    private $secret;
                    function __construct($secret) { $this->secret = $secret; }
                    function view($seed) {
                        extract(['value' => $seed, 'delta' => 3]);
                        $result = include __DIR__ . '/view.php';
                        $name = 'value'; $$name += 2;
                        return "$result:$value:$made:" . $closure() . ':' . json_encode(compact('value', 'delta'));
                    }
                }
                class B extends A { const TAG = 'B'; }
                $seed = (int) $_GET['seed'];
                $box = $seed % 2 ? new A($seed * 10) : new B($seed * 10);
                echo $box->view($seed);
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            int threads = 16;
            CountDownLatch ready = new CountDownLatch(threads), start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            var futures = new ArrayList<Future<String>>();
            try {
                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    futures.add(pool.submit(() -> {
                        ready.countDown(); start.await();
                        for (int run = 0; run < 20; run++) {
                            int seed = thread * 20 + run + 1;
                            PhpRequest request = new PhpRequest();
                            request.get.put("seed", String.valueOf(seed));
                            PhpEngine.Result result = engine.execute(script, request);
                            String expected = "A:" + (seed % 2 == 0 ? "B" : "A") + ":" + (seed + 5)
                                    + ":" + (seed * 11 + 3) + ":" + (seed * 11 + 6)
                                    + ":{\"value\":" + (seed + 6) + ",\"delta\":3}";
                            if (result.status() != 200 || !expected.equals(result.bodyText())) {
                                return "seed=" + seed + " expected=" + expected + " actual=" + result.bodyText() + " errors=" + result.errors();
                            }
                        }
                        return "OK";
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                for (Future<String> future : futures) { assertEquals("OK", future.get(90, TimeUnit.SECONDS)); }
            } finally { pool.shutdownNow(); }
        }
    }
}
