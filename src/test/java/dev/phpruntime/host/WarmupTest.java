package dev.phpruntime.host;

import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WarmupTest {

    @Test
    void warmupRunsTheGivenPathsThroughTheDispatcher() throws Exception {
        Path root = Files.createTempDirectory("warmup");
        Files.writeString(root.resolve("a.php"), "<?php echo 'a';");
        Files.writeString(root.resolve("b.php"), "<?php echo $_GET['x'] ?? '';");
        Scheduler scheduler = new Scheduler(2, 10, 30_000);
        PhpEngine engine = new PhpEngine(scheduler);
        PhpDispatcher dispatcher = new PhpDispatcher(root, engine, null);
        try {
            String summary = Warmup.run(dispatcher, List.of("/a.php", "/b.php?x=1", "/missing.php"),
                    "localhost:8080", 8080, 2, 1);
            assertTrue(summary.contains("200="), summary);
            // A missing script falls back to index.php, which does not exist here either.
            assertTrue(summary.contains("404="), summary);
        } finally {
            dispatcher.close();
            engine.close();
            scheduler.close();
        }
    }
}
