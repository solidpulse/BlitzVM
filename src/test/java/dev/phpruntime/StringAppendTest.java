package dev.phpruntime;

import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpStringBuffer;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class StringAppendTest {
    @Test
    void snapshotsAreImmutableAndCachedUntilTheNextAppend() {
        PhpStringBuffer buffer = new PhpStringBuffer("\0\u00ff");
        buffer.append("ab");
        String copy = buffer.asString();
        assertSame(copy, buffer.asString());
        buffer.append("cd");
        assertEquals("\0\u00ffab", copy);
        assertEquals("\0\u00ffabcd", buffer.asString());
        buffer.append("");
        assertSame(buffer.asString(), buffer.asString());
    }

    @Test
    void cellAccessorsFlattenAndNeverShareBuffersWithAnotherCell() {
        PhpReference cell = new PhpReference("a");
        cell.appendString("b", false);
        Object copy = cell.getValue();
        assertInstanceOf(String.class, copy);
        assertSame(copy, cell.getValue());
        PhpReference other = new PhpReference(copy);
        cell.appendString("c", false);
        other.appendString("d", false);
        assertEquals("abc", cell.getValue());
        assertEquals("abd", other.getValue());
        PhpStrings.checkValue(cell, "cell read");
        cell.setValue("reset"); cell.appendString("!", false);
        assertEquals("reset!", cell.getValue());
    }

    @Test
    void builtinArgumentsAndResultsRejectEscapedBuffers() {
        assertTrue(PhpStrings.CHECK, "Tests must enable the storage-escape guard");
        PhpStringBuffer buffer = new PhpStringBuffer("bad");
        var argument = new Builtins.Def("append_guard_argument", 1, 1, 0, (ctx, args) -> {
            fail("An escaped buffer must be rejected before running a builtin");
            return PhpNull.INSTANCE;
        });
        assertThrows(IllegalStateException.class, () -> argument.call(null, new Object[]{buffer}));
        var result = new Builtins.Def("append_guard_result", 0, 0, 0, (ctx, args) -> buffer);
        assertThrows(IllegalStateException.class, () -> result.call(null, new Object[0]));
    }

    @Test
    void onlyAppendTargetsCheckPlainSlotsForBuffers() {
        Builtins.def("__append_shape_check", 0, 0, (ctx, args) -> {
            var root = ctx.lookupFunction("append_shape").callTarget().getRootNode();
            root.adoptChildren();
            int[] counts = new int[2];
            root.accept(node -> {
                if (node instanceof LocalVarNode local) {
                    assertEquals(local.varName().equals("s"), local.kind().isAppendTarget(), local.varName());
                    counts[local.kind().isAppendTarget() ? 1 : 0]++;
                }
                return true;
            });
            assertTrue(counts[0] > 0 && counts[1] > 0);
            return PhpNull.INSTANCE;
        });
        try (var ctx = org.graalvm.polyglot.Context.newBuilder("php").build()) {
            ctx.eval("php", "<?php function append_shape($n) { $s = ''; for ($i = 0; $i < $n; $i++) { $s .= 'ab'; } return $s; } __append_shape_check();");
        }
    }

    @Test
    void sharedEngineKeepsLocalCellAndIncludeBuffersRequestOwned(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("view.php"), "<?php $s .= '-include'; $copy = $s; $s .= '!'; return $copy;");
        Path script = Files.writeString(dir.resolve("main.php"), """
                <?php
                function part(&$s, $seed) { $s .= $seed; }
                function local($seed) {
                    $s = ''; for ($i = 0; $i < 30; $i++) { $s .= $seed; }
                    $copy = $s; part($s, '-ref'); return strlen($copy) . ':' . $s;
                }
                function dynamic($seed) {
                    $s = $seed; $s .= '-scope'; $copy = include __DIR__ . '/view.php';
                    $name = 's'; $$name .= '?'; return $copy . ':' . $s;
                }
                function generated($seed) { $s = $seed; $s .= 'a'; yield $s; $s .= 'b'; yield $s; }
                $seed = $_GET['seed']; $global = $seed; $global .= '-global';
                echo local($seed), '|', dynamic($seed), '|', $global, '|';
                foreach (generated($seed) as $value) { echo $value, ','; }
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            int threads = 16;
            var ready = new CountDownLatch(threads); var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            var futures = new ArrayList<Future<String>>();
            try {
                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    futures.add(pool.submit(() -> {
                        ready.countDown(); start.await();
                        for (int run = 0; run < 20; run++) {
                            String seed = "s" + (thread * 20 + run);
                            PhpRequest request = new PhpRequest(); request.get.put("seed", seed);
                            var result = engine.execute(script, request);
                            String expected = seed.length() * 30 + ":" + seed.repeat(30) + "-ref|"
                                    + seed + "-scope-include:" + seed + "-scope-include!?|"
                                    + seed + "-global|" + seed + "a," + seed + "ab,";
                            if (result.status() != 200 || !expected.equals(result.bodyText())) {
                                return "expected=" + expected + " actual=" + result.bodyText() + " errors=" + result.errors();
                            }
                        }
                        return "OK";
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                for (var future : futures) { assertEquals("OK", future.get(90, TimeUnit.SECONDS)); }
            } finally { pool.shutdownNow(); }
        }
    }
}
