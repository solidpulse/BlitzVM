package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The named-argument fast path binds per call site, so a monomorphic site never rescans names. */
class NamedArgumentsTest {

    @Test
    void monomorphicNamedSiteStaysOnFastPath(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("mono.php"), """
                <?php
                function target($alpha, $beta, $gamma = 3) {
                    return $alpha + $beta + $gamma;
                }
                $sum = 0;
                for ($i = 0; $i < 200; $i++) {
                    $sum += target(beta: $i, alpha: 2);
                }
                echo $sum, "\\n";
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            CallArgHelper.resetGenericNamedBindingCount();
            PhpEngine.Result result = engine.execute(script, new PhpRequest());
            assertEquals(200, result.status(), result.bodyText());
            assertEquals("20900\n", result.bodyText());
            assertEquals(0, CallArgHelper.genericNamedBindingCount(),
                    "a monomorphic named call site used the generic findParamIndex binding");
        }
    }

    @Test
    void monomorphicMethodClosureAndConstructorSitesStayOnFastPath(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("kinds.php"), """
                <?php
                class Box {
                    public function __construct(public int $left = 0, public int $right = 0, public int $extra = 1) {}
                    public function add($left, $right, $extra = 1) {
                        return $left + $right + $extra;
                    }
                }
                $box = new Box(right: 4, left: 3);
                $closure = function ($left, $right, $extra = 1) {
                    return $left + $right + $extra;
                };
                $sum = 0;
                for ($i = 0; $i < 100; $i++) {
                    $sum += $box->add(right: $i, left: 2);
                    $sum += $closure(right: $i, left: 2);
                }
                $made = new Box(left: 1, right: 2);
                echo $sum + $box->left + $box->right + $box->extra
                    + $made->left + $made->right + $made->extra, "\\n";
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            CallArgHelper.resetGenericNamedBindingCount();
            PhpEngine.Result result = engine.execute(script, new PhpRequest());
            assertEquals(200, result.status(), result.bodyText());
            assertEquals("10512\n", result.bodyText());
            assertEquals(0, CallArgHelper.genericNamedBindingCount(),
                    "a monomorphic named call site of a method, closure or constructor used the generic binding");
        }
    }

    @Test
    void spreadCallStillUsesGenericBinding(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("spread.php"), """
                <?php
                function target($alpha, $beta) {
                    return $alpha + $beta;
                }
                $args = [1];
                for ($i = 0; $i < 3; $i++) {
                    echo target(...$args, beta: 2), "\\n";
                }
                """);
        try (PhpEngine engine = new PhpEngine(null)) {
            CallArgHelper.resetGenericNamedBindingCount();
            PhpEngine.Result result = engine.execute(script, new PhpRequest());
            assertEquals(200, result.status(), result.bodyText());
            assertEquals("3\n3\n3\n", result.bodyText());
            assertTrue(CallArgHelper.genericNamedBindingCount() > 0,
                    "the spread path should still go through the generic binding");
        }
    }
}
