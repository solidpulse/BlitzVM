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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests Phase A/B error paths, visibility rules, and ContextPolicy.SHARED class isolation. */
class ClassObjectsTest {

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
    void redeclareClassThrowsFatalError(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("redeclare.php"), """
                <?php
                class Dup {}
                class dup {}
                """);
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());
        assertTrue(res.bodyText().contains("Cannot declare class dup, because the name is already in use"),
                res.bodyText());
    }

    @Test
    void instantiateAbstractClassOrInterfaceThrows(@TempDir Path dir) throws Exception {
        Path abs = Files.writeString(dir.resolve("abs.php"), """
                <?php
                abstract class Base { abstract public function f(); }
                new Base();
                """);
        PhpEngine.Result r1 = engine.execute(abs, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("Cannot instantiate abstract class Base"), r1.bodyText());

        Path iface = Files.writeString(dir.resolve("iface.php"), """
                <?php
                interface I { public function f(); }
                new I();
                """);
        PhpEngine.Result r2 = engine.execute(iface, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("Cannot instantiate interface I"), r2.bodyText());
    }

    @Test
    void echoObjectWithoutToStringThrows(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("echo_obj.php"), """
                <?php
                class Plain {}
                echo new Plain();
                """);
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());
        assertTrue(res.bodyText().contains("Object of class Plain could not be converted to string"),
                res.bodyText());
    }

    @Test
    void undefinedPropertyWarnsAndReturnsNull(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("undef_prop.php"), """
                <?php
                class C {}
                $c = new C();
                var_dump($c->nope);
                """);
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals("NULL\n", res.bodyText());
        assertTrue(res.errors().contains("Undefined property: C::$nope"), res.errors());
    }

    @Test
    void privateAndProtectedVisibilityEnforced(@TempDir Path dir) throws Exception {
        Path privRead = Files.writeString(dir.resolve("priv.php"), """
                <?php
                class Secret { private $x = 1; }
                echo (new Secret())->x;
                """);
        PhpEngine.Result r1 = engine.execute(privRead, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("Cannot access private property Secret::$x"), r1.bodyText());

        Path protWrite = Files.writeString(dir.resolve("prot.php"), """
                <?php
                class Secret { protected $y = 1; }
                (new Secret())->y = 2;
                """);
        PhpEngine.Result r2 = engine.execute(protWrite, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("Cannot access protected property Secret::$y"), r2.bodyText());
    }

    @Test
    void getClassAndParentWithoutArgsInMethod(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("noarg_cls.php"), """
                <?php
                class P {}
                class C extends P {
                    public function info() {
                        return get_class() . ':' . get_parent_class();
                    }
                }
                echo (new C())->info();
                """);
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals("C:P", res.bodyText());
    }

    @Test
    void classesAreIsolatedAcrossSharedRequests(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("req_a.php"), """
                <?php
                class ReqItem {
                    public static int $count = 0;
                    public int $v = 1;
                }
                ReqItem::$count += 5;
                $o = new ReqItem();
                echo ReqItem::$count, ':', spl_object_id($o);
                """);
        Path b = Files.writeString(dir.resolve("req_b.php"), """
                <?php
                class ReqItem {
                    public static int $count = 10;
                    public int $v = 2;
                }
                ReqItem::$count++;
                $o = new ReqItem();
                echo ReqItem::$count, ':', spl_object_id($o);
                """);
        Path c = Files.writeString(dir.resolve("req_c.php"), """
                <?php
                var_dump(class_exists('ReqItem'));
                """);
        PhpEngine.Result ra1 = engine.execute(a, new PhpRequest());
        assertEquals(200, ra1.status());
        assertEquals("5:1", ra1.bodyText());

        PhpEngine.Result rc = engine.execute(c, new PhpRequest());
        assertEquals(200, rc.status());
        assertEquals("bool(false)\n", rc.bodyText());

        PhpEngine.Result rb = engine.execute(b, new PhpRequest());
        assertEquals(200, rb.status());
        assertEquals("11:1", rb.bodyText());

        PhpEngine.Result ra2 = engine.execute(a, new PhpRequest());
        assertEquals(200, ra2.status());
        assertEquals("5:1", ra2.bodyText());
    }

    @Test
    void unimplementedInterfaceMethodAllowedAtDeclaration(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("unimpl.php"), """
                <?php
                interface Iface { public function foo(); }
                class Loose implements Iface { public $x = 1; }
                $l = new Loose();
                var_dump($l instanceof Iface, $l->x);
                """);
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(200, res.status());
        assertEquals("bool(true)\nint(1)\n", res.bodyText());
    }

    @Test
    void staticCallErrorsAndToStringReturnValidation(@TempDir Path dir) throws Exception {
        Path nonStatic = Files.writeString(dir.resolve("nonstatic.php"), """
                <?php
                class Foo { public function inst() {} }
                Foo::inst();
                """);
        PhpEngine.Result r1 = engine.execute(nonStatic, new PhpRequest());
        assertEquals(500, r1.status());
        assertTrue(r1.bodyText().contains("Non-static method Foo::inst() cannot be called statically"), r1.bodyText());

        Path badToString = Files.writeString(dir.resolve("bad_tostr.php"), """
                <?php
                class BadStr { public function __toString() { return 123; } }
                echo new BadStr();
                """);
        PhpEngine.Result r2 = engine.execute(badToString, new PhpRequest());
        assertEquals(500, r2.status());
        assertTrue(r2.bodyText().contains("BadStr::__toString() must return a string value"), r2.bodyText());
    }

    @Test
    void reincludeFileWithClassThrowsRedeclaration(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("inc_cls.php"), "<?php class Twice {}");
        Path script = Files.writeString(dir.resolve("main.php"), """
                <?php
                include __DIR__ . '/inc_cls.php';
                include __DIR__ . '/inc_cls.php';
                """);
        PhpEngine.Result res = engine.execute(script, new PhpRequest());
        assertEquals(500, res.status());
        assertTrue(res.bodyText().contains("Cannot declare class Twice, because the name is already in use"),
                res.bodyText());
    }

    @Test
    void anonymousClassIsolatedAcrossSharedRequests(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("anon_req.php"), """
                <?php
                for ($i = 0; $i < 2; $i++) {
                    $o = new class($i) {
                        public static int $calls = 0;
                        public int $v;
                        public function __construct(int $v) {
                            $this->v = $v;
                            self::$calls++;
                        }
                    };
                }
                echo $o::$calls, ':', $o->v, ':', spl_object_id($o);
                """);
        PhpEngine.Result r1 = engine.execute(script, new PhpRequest());
        assertEquals(200, r1.status());
        assertEquals("2:1:2", r1.bodyText());

        PhpEngine.Result r2 = engine.execute(script, new PhpRequest());
        assertEquals(200, r2.status());
        assertEquals("2:1:2", r2.bodyText());
    }
}
