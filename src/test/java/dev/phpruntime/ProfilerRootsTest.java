package dev.phpruntime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every PHP function, method, static method and closure is its own root, tagged with
 * {@code StandardTags.RootTag}, with the name and source line a CPU sampler shows.
 */
class ProfilerRootsTest {

    private static final String SCRIPT = """
            <?php
            function doubleIt($n) {
                return $n * 2;
            }
            class Greeter {
                public function greet($name) {
                    return "hi " . $name;
                }
                public static function shout($s) {
                    return $s . "!";
                }
            }
            $g = new Greeter();
            $x = doubleIt(4);
            $y = $g->greet("a");
            $z = Greeter::shout("b");
            $f = function ($v) {
                return $v + 1;
            };
            $w = $f(1);
            echo $x, $y, $z, $w, "\\n";
            """;

    @Test
    void functionMethodStaticMethodAndClosureAreNamedRoots() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Engine engine = Engine.newBuilder("php")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
             Context context = Context.newBuilder("php")
                     .engine(engine)
                     .allowAllAccess(true)
                     .out(out)
                     .err(new ByteArrayOutputStream())
                     .build()) {
            RootProbeInstrument.Recorder recorder =
                    engine.getInstruments().get(RootProbeInstrument.ID)
                            .lookup(RootProbeInstrument.Recorder.class);

            context.eval(Source.newBuilder("php", SCRIPT, "roots.php").build());

            assertEquals("8hi ab!2\n", out.toString(StandardCharsets.UTF_8));
            Map<String, RootProbeInstrument.Root> roots = byName(recorder);

            assertRoot(roots, "doubleIt", 2);
            assertRoot(roots, "Greeter::greet", 6);
            assertRoot(roots, "Greeter::shout", 9);
            assertRoot(roots, "{closure:roots.php:17}", 17);
            assertRoot(roots, "{main} roots.php", 1);
        }
    }

    private static Map<String, RootProbeInstrument.Root> byName(RootProbeInstrument.Recorder recorder) {
        Map<String, RootProbeInstrument.Root> roots = new LinkedHashMap<>();
        for (RootProbeInstrument.Root root : recorder.roots()) {
            roots.putIfAbsent(root.name(), root);
        }
        return roots;
    }

    private static void assertRoot(Map<String, RootProbeInstrument.Root> roots, String name, int line) {
        RootProbeInstrument.Root root = roots.get(name);
        assertTrue(root != null, "no root named " + name + ", saw " + roots.keySet());
        assertEquals(line, root.line(), "line of root " + name);
        assertEquals("roots.php", root.source(), "source of root " + name);
    }
}
