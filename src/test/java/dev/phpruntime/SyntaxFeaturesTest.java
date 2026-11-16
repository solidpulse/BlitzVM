package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests error paths and concurrent shared-engine execution for Task 06 features. */
class SyntaxFeaturesTest {

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
    void heredocIndentationErrorsMatchPhp(@TempDir Path dir) throws Exception {
        Path mixedClose = Files.writeString(dir.resolve("mixed_close.php"),
                "<?php\n$x = <<<EOT\n    a\n \tEOT;\n");
        PhpEngine.Result r1 = engine.execute(mixedClose, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("Invalid indentation - tabs and spaces cannot be mixed"), r1.bodyText());

        Path mixedBody = Files.writeString(dir.resolve("mixed_body.php"),
                "<?php\n$x = <<<EOT\n\t a\n    EOT;\n");
        PhpEngine.Result r2 = engine.execute(mixedBody, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("Invalid indentation - tabs and spaces cannot be mixed"), r2.bodyText());

        Path lowIndent = Files.writeString(dir.resolve("low_indent.php"),
                "<?php\n$x = <<<EOT\n  a\n    EOT;\n");
        PhpEngine.Result r3 = engine.execute(lowIndent, new PhpRequest());
        assertEquals(500, r3.status());
        assertTrue(r3.bodyText().contains("Invalid body indentation level (expecting an indentation level of at least 4)"),
                r3.bodyText());
    }

    @Test
    void gotoCompileErrors(@TempDir Path dir) throws Exception {
        Path undef = Files.writeString(dir.resolve("undef_lbl.php"), "<?php goto nope;");
        PhpEngine.Result r1 = engine.execute(undef, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("'goto' to undefined label 'nope'"), r1.bodyText());

        Path intoLoop = Files.writeString(dir.resolve("into_loop.php"),
                "<?php goto inside; while (false) { inside: echo 1; }");
        PhpEngine.Result r2 = engine.execute(intoLoop, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("'goto' into loop or switch statement is disallowed"), r2.bodyText());

        Path outFinally = Files.writeString(dir.resolve("out_finally.php"),
                "<?php try {} finally { goto out; } out: echo 1;");
        PhpEngine.Result r3 = engine.execute(outFinally, new PhpRequest());
        assertEquals(500, r3.status());
        assertTrue(r3.bodyText().contains("jump out of a finally block is disallowed"), r3.bodyText());

        Path inFinally = Files.writeString(dir.resolve("in_finally.php"),
                "<?php goto inf; try {} finally { inf: echo 1; }");
        PhpEngine.Result r4 = engine.execute(inFinally, new PhpRequest());
        assertEquals(500, r4.status());
        assertTrue(r4.bodyText().contains("jump into a finally block is disallowed"), r4.bodyText());

        Path dupLabel = Files.writeString(dir.resolve("dup_lbl.php"), "<?php a: a: echo 1;");
        PhpEngine.Result r5 = engine.execute(dupLabel, new PhpRequest());
        assertEquals(500, r5.status());
        assertTrue(r5.bodyText().contains("Label 'a' already defined"), r5.bodyText());
    }

    @Test
    void traitAndMatchErrors(@TempDir Path dir) throws Exception {
        Path newTrait = Files.writeString(dir.resolve("new_trait.php"), "<?php trait Tr {} new Tr();");
        PhpEngine.Result r1 = engine.execute(newTrait, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("Cannot instantiate trait Tr"), r1.bodyText());

        Path collision = Files.writeString(dir.resolve("collision.php"),
                "<?php trait A { function f(){} } trait B { function f(){} } class C { use A, B; }");
        PhpEngine.Result r2 = engine.execute(collision, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("collision"), r2.bodyText());

        Path uncaughtMatch = Files.writeString(dir.resolve("unhandled_match.php"),
                "<?php echo match (5) { 1 => 'one' };");
        PhpEngine.Result r3 = engine.execute(uncaughtMatch, new PhpRequest());
        assertEquals(500, r3.status());
        assertTrue(r3.bodyText().contains("UnhandledMatchError: Unhandled match case of type int"), r3.bodyText());
    }

    @Test
    void concurrentSharedEngineNamespacesTraitsAndGoto(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ns_common.php"), """
                <?php
                namespace App;
                function run($s) {
                    return strlen($s);
                }
                """);
        Path reqGlobalStrlen = Files.writeString(dir.resolve("req_global.php"), """
                <?php
                include __DIR__ . '/ns_common.php';
                echo \\App\\run('hello');
                """);
        Path reqNsStrlen = Files.writeString(dir.resolve("req_ns.php"), """
                <?php
                namespace App {
                    function strlen($s) { return 100 + \\strlen($s); }
                }
                namespace {
                    include __DIR__ . '/ns_common.php';
                    echo \\App\\run('hello');
                }
                """);
        Path traitAndGoto = Files.writeString(dir.resolve("trait_goto.php"), """
                <?php
                trait SharedTr {
                    private $tag = 'ok';
                    public function compute($n) {
                        $i = 0;
                        $acc = 0;
                        again:
                        if ($i < $n) {
                            $acc += $i;
                            $i++;
                            goto again;
                        }
                        return __CLASS__ . ':' . $this->tag . ':' . $acc;
                    }
                }
                class One { use SharedTr; }
                class Two { use SharedTr; }
                echo (new One())->compute(5), '|', (new Two())->compute(4);
                """);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int idx = i;
                tasks.add(() -> {
                    if (idx % 3 == 0) {
                        PhpEngine.Result res = engine.execute(reqGlobalStrlen, new PhpRequest());
                        assertEquals(200, res.status());
                        assertEquals("5", res.bodyText());
                    } else if (idx % 3 == 1) {
                        PhpEngine.Result res = engine.execute(reqNsStrlen, new PhpRequest());
                        assertEquals(200, res.status());
                        assertEquals("105", res.bodyText());
                    } else {
                        PhpEngine.Result res = engine.execute(traitAndGoto, new PhpRequest());
                        assertEquals(200, res.status());
                        assertEquals("One:ok:10|Two:ok:6", res.bodyText());
                    }
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
}
