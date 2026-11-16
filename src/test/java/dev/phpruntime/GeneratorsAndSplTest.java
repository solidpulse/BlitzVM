package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorsAndSplTest {
    @Test
    void sharedGeneratorIteratorAndArrayAccessAstIsRequestLocal(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("shared.php"), """
                <?php
                class Bag implements ArrayAccess, Iterator, Countable {
                    public $values;
                    private $i = 0;
                    public function __construct($seed) { $this->values = [$seed, $seed+1]; }
                    public function offsetExists($k): bool { return isset($this->values[$k]); }
                    public function &offsetGet($k): mixed { return $this->values[$k]; }
                    public function offsetSet($k,$v): void { $this->values[$k]=$v; }
                    public function offsetUnset($k): void { unset($this->values[$k]); }
                    public function count(): int { return count($this->values); }
                    public function rewind(): void { $this->i=0; }
                    public function valid(): bool { return $this->i<count($this->values); }
                    public function key(): mixed { return $this->i; }
                    public function current(): mixed { return $this->values[$this->i]; }
                    public function next(): void { $this->i++; }
                    public function generate(&$x) {
                        try {
                            foreach ($this as $key=>$value) {
                                $sent = yield $key=>$value+$x;
                                $x += $sent;
                            }
                            return $x;
                        } finally { $this[0]++; }
                    }
                }
                $seed=(int)$_GET['seed'];
                $bag=new Bag($seed);
                $x=$seed*2;
                $g=$bag->generate($x);
                $a=$g->current();
                $b=$g->send(3);
                $g->send(4);
                echo "$a:$b:$x:", $g->getReturn(), ':', $bag[0], ':', count($bag);
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            int threads = 16;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(threads);
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            try {
                for (int t = 0; t < threads; t++) {
                    final int thread = t;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        for (int run = 0; run < 20; run++) {
                            int seed = thread * 100 + run;
                            PhpRequest request = new PhpRequest();
                            request.get.put("seed", String.valueOf(seed));
                            var result = engine.execute(script, request);
                            String expected = (seed * 3) + ":" + (seed * 3 + 4) + ":"
                                    + (seed * 2 + 7) + ":" + (seed * 2 + 7) + ":" + (seed + 1) + ":2";
                            if (result.status() != 200 || !expected.equals(result.bodyText())) {
                                return "expected=" + expected + " actual=" + result.bodyText() + " errors=" + result.errors();
                            }
                        }
                        return "OK";
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                for (var future : futures) {
                    assertEquals("OK", future.get(90, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void generatorErrorsAndDirectTraversableDeclaration(@TempDir Path dir) throws Exception {
        Path errors = Files.writeString(dir.resolve("errors.php"), """
                <?php
                function values() { yield 1; yield 2; }
                $g=values();
                try { $g->getReturn(); } catch (Error $e) { echo $e->getMessage(), '|'; }
                $g->current(); $g->next();
                try { $g->rewind(); } catch (Error $e) { echo $e->getMessage(), '|'; }
                $g->next();
                try { foreach($g as $v) {} } catch (Exception $e) { echo $e->getMessage(), '|'; }
                function recursiveGenerator() { global $g; $g->current(); yield 1; }
                $g=recursiveGenerator();
                try { $g->current(); } catch (Error $e) { echo $e->getMessage(), '|'; }
                """);
        Path declaration = Files.writeString(dir.resolve("traversable.php"), "<?php class Invalid implements Traversable {}");
        try (PhpEngine engine = new PhpEngine(null)) {
            var result = engine.execute(errors, new PhpRequest());
            assertEquals(200, result.status(), result.bodyText());
            assertEquals("Cannot get return value of a generator that hasn't returned|"
                    + "Cannot rewind a generator that was already run|Cannot traverse an already closed generator|"
                    + "Cannot resume an already running generator|", result.bodyText());
            var invalid = engine.execute(declaration, new PhpRequest());
            assertEquals(500, invalid.status());
            assertTrue(invalid.bodyText().contains("must implement interface Traversable as part of either Iterator or IteratorAggregate"));
        }
    }

    @Test
    void generatorSafepointsHonourSchedulerTimeout(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("generator-timeout.php"), """
                <?php
                function busyGenerator() {
                    try { while (true) { yield 1; } }
                    catch (Throwable $e) { echo 'caught'; }
                    finally { echo 'finally'; }
                }
                foreach (busyGenerator() as $value) {}
                """);
        try (var scheduler = new dev.phpruntime.sched.Scheduler(1, 10, 300);
             PhpEngine engine = new PhpEngine(scheduler)) {
            var result = engine.execute(script, new PhpRequest());
            assertEquals(500, result.status());
            assertTrue(result.bodyText().contains("Maximum execution time"), result.bodyText());
            assertTrue(!result.bodyText().contains("caught") && !result.bodyText().contains("finally"), result.bodyText());
        }
    }
}
