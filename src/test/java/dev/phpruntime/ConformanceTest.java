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

    /**
     * The worktree path a fixture ran from. Some traces carry closure names, and those
     * include the absolute file the fixture lives in; normalizing the prefix keeps the
     * fixture's expected output valid in any checkout.
     */
    private static final java.util.regex.Pattern REPO_PATH =
            java.util.regex.Pattern.compile("/[^\\s(){}\\[\\]]*?/src/test/resources/php/");

    private static byte[] normalize(byte[] output) {
        String text = new String(output, java.nio.charset.StandardCharsets.UTF_8);
        return REPO_PATH.matcher(text).replaceAll("<dir>/")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void check(Path script) throws IOException {
        Path expectedFile = Path.of(script.toString().replaceAll("\\.php$", ".out"));
        byte[] expected = Files.readAllBytes(expectedFile);
        PhpEngine.Result result = engine.execute(script, new PhpRequest());
        assertArrayEquals(normalize(expected), normalize(result.body()),
                "output of " + script.getFileName() + "\nstderr: " + result.errors());
    }
}
