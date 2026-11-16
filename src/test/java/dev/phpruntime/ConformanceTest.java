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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Runs every src/test/resources/php/*.php and compares with the output real php produced. */
class ConformanceTest {

    private static final Path DIR = Path.of("src/test/resources/php");
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
                .filter(p -> p.toString().endsWith(".php"))
                .sorted()
                .map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(p)));
    }

    private static void check(Path script) throws IOException {
        Path expectedFile = Path.of(script.toString().replaceAll("\\.php$", ".out"));
        byte[] expected = Files.readAllBytes(expectedFile);
        PhpEngine.Result result = engine.execute(script, new PhpRequest());
        assertArrayEquals(expected, result.body(), "output of " + script.getFileName() + "\nstderr: " + result.errors());
    }
}
