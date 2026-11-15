package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests error paths, exact version/error constants, and ContextPolicy.SHARED isolation. */
class IncludeConstantsTest {

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
    void exactPhp83Constants(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("ver.php"),
                "<?php echo PHP_VERSION_ID, ':', PHP_MAJOR_VERSION, ':', PHP_MINOR_VERSION, ':', E_ALL;");
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals("80300:8:3:32767", res.bodyText());
    }

    @Test
    void undefinedConstantIsRuntimeErrorNotParseError(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("undef.php"),
                "<?php echo \"before\\n\"; echo NO_SUCH_CONST;");
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());
        assertTrue(res.bodyText().startsWith("before\n"), res.bodyText());
        assertTrue(res.bodyText().contains("Undefined constant \"NO_SUCH_CONST\""), res.bodyText());
    }

    @Test
    void constantBuiltinThrowsOnUndefined(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("const_fn.php"),
                "<?php echo constant('NO_SUCH_CONST');");
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());
        assertTrue(res.bodyText().contains("Undefined constant \"NO_SUCH_CONST\""), res.bodyText());
    }

    @Test
    void redefineConstantWarnsAndKeepsOriginal(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("redef.php"),
                "<?php var_dump(define('DUP', 'first'), define('DUP', 'second'), define('PHP_EOL', 'x')); echo DUP;");
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals("bool(true)\nbool(false)\nbool(false)\nfirst", res.bodyText());
        assertTrue(res.errors().contains("Constant DUP already defined"), res.errors());
        assertTrue(res.errors().contains("Constant PHP_EOL already defined"), res.errors());
    }

    @Test
    void constInsideFunctionIsParseError(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("bad_const.php"),
                "<?php function f() { const X = 1; }");
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());
        assertTrue(res.bodyText().contains("PHP Parse error"), res.bodyText());
    }

    @Test
    void missingIncludeWarnsAndReturnsFalse(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("miss_inc.php"),
                "<?php var_dump(include 'no_such_file.php', include_once 'no_such_file.php');");
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals("bool(false)\nbool(false)\n", res.bodyText());
        assertTrue(res.errors().contains("Failed opening 'no_such_file.php'"), res.errors());
    }

    @Test
    void missingRequireIsFatal(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("miss_req.php"),
                "<?php require 'no_such_req.php';");
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());
        assertTrue(res.bodyText().contains("Failed opening required 'no_such_req.php'"), res.bodyText());
    }

    @Test
    void cannotRedeclareBuiltinOrUserFunction(@TempDir Path dir) throws Exception {
        Path builtinDup = Files.writeString(dir.resolve("dup_builtin.php"),
                "<?php function strlen() {}");
        PhpEngine.Result r1 = engine.execute(builtinDup, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("Cannot redeclare function strlen()"), r1.bodyText());

        Path userDup = Files.writeString(dir.resolve("dup_user.php"),
                "<?php function foo() {} function FOO() {}");
        PhpEngine.Result r2 = engine.execute(userDup, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("Cannot redeclare function FOO()"), r2.bodyText());
    }

    @Test
    void blockFunctionNotHoistedAndUnreachedIfFalseUndefined(@TempDir Path dir) throws Exception {
        Path blockFn = Files.writeString(dir.resolve("block_fn.php"),
                "<?php { var_dump(function_exists('blk')); function blk() {} var_dump(function_exists('blk')); }");
        PhpEngine.Result r1 = engine.execute(blockFn, new PhpRequest());
        assertEquals(200, r1.status());
        assertEquals("bool(false)\nbool(true)\n", r1.bodyText());

        Path unreached = Files.writeString(dir.resolve("unreached.php"),
                "<?php if (false) { function ghost() {} } ghost();");
        PhpEngine.Result r2 = engine.execute(unreached, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("Call to undefined function ghost()"), r2.bodyText());
    }

    @Test
    void sharedEngineIsolatesContextsAcrossRequests(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lib.php"), "<?php echo 'L'; return 'loaded';");
        Path script = Files.writeString(dir.resolve("app.php"), """
                <?php
                if (($_GET['mode'] ?? '') === 'a') {
                    define('REQ_ONLY', 'val_a');
                    function poly_branch() { return 'branch_a'; }
                    function maybe_only() { return 'present'; }
                    $inc = include_once __DIR__ . '/lib.php';
                    echo REQ_ONLY, '|', poly_branch(), '|', maybe_only(), '|', $inc;
                } else {
                    function poly_branch() { return 'branch_b'; }
                    $inc = include_once __DIR__ . '/lib.php';
                    var_dump(defined('REQ_ONLY'), function_exists('maybe_only'));
                    echo poly_branch(), '|', $inc;
                }
                """);

        PhpRequest reqA = new PhpRequest();
        reqA.get.put("mode", "a");
        PhpEngine.Result resA = engine.execute(script, reqA);
        assertEquals(200, resA.status());
        assertEquals("Lval_a|branch_a|present|loaded", resA.bodyText());

        PhpRequest reqB = new PhpRequest();
        reqB.get.put("mode", "b");
        PhpEngine.Result resB = engine.execute(script, reqB);
        assertEquals(200, resB.status());
        assertEquals("Lbool(false)\nbool(false)\nbranch_b|loaded", resB.bodyText());

        // Calling an undefined conditional function on a second run of the same cached AST
        Path condCall = Files.writeString(dir.resolve("cond_call.php"), """
                <?php
                if (($_GET['def'] ?? '') === '1') {
                    function dyn_fn() { return 'ok'; }
                }
                echo dyn_fn();
                """);
        PhpRequest r1 = new PhpRequest();
        r1.get.put("def", "1");
        assertEquals("ok", engine.execute(condCall, r1).bodyText());

        PhpRequest r2 = new PhpRequest();
        r2.get.put("def", "0");
        PhpEngine.Result failRes = engine.execute(condCall, r2);
        assertEquals(500, failRes.status());
        assertFalse(failRes.bodyText().contains("ok"));
        assertTrue(failRes.bodyText().contains("Call to undefined function dyn_fn()"), failRes.bodyText());
    }
}
