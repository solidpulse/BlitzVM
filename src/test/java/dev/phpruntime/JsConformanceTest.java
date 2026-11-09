package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs every src/test/resources/js/*.js and compares with the output real Node.js produced. */
class JsConformanceTest {

    private static final Path DIR = Path.of("src/test/resources/js");
    private static PhpEngine engine;

    @BeforeAll
    static void setUp() {
        engine = new PhpEngine(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @TestFactory
    Stream<DynamicTest> fixtures() throws IOException {
        return Files.list(DIR)
                .filter(p -> p.toString().endsWith(".js"))
                .sorted()
                .map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(p)));
    }

    private static void check(Path script) throws IOException {
        Path expectedFile = Path.of(script.toString().replaceAll("\\.js$", ".out"));
        String expected = Files.readString(expectedFile);
        PhpEngine.Result result = engine.execute(script, new PhpRequest());
        assertEquals(expected, result.bodyText(), "output of " + script.getFileName() + "\nstderr: " + result.errors());
    }
}
