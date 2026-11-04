package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With a single CPU slot, a busy loop must not starve other requests. */
class SchedulerTest {

    @Test
    void infiniteLoopIsPreemptedAndTimedOut(@TempDir Path dir) throws Exception {
        Path forever = Files.writeString(dir.resolve("forever.php"), "<?php while (true) { }");
        Path hello = Files.writeString(dir.resolve("hello.php"), "<?php echo 'Hello, World!';");
        try (Scheduler scheduler = new Scheduler(1, 10, 2000); PhpEngine engine = new PhpEngine(scheduler)) {
            CompletableFuture<PhpEngine.Result> hog = CompletableFuture.supplyAsync(() -> run(engine, forever));
            Thread.sleep(300); // let the hog take the only slot

            long start = System.nanoTime();
            PhpEngine.Result greeting = run(engine, hello);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals("Hello, World!", greeting.bodyText());
            assertTrue(millis < 1000, "hello took " + millis + "ms while a hog held the only CPU slot");
            assertTrue(scheduler.preemptions() > 0, "expected the hog to be preempted");

            PhpEngine.Result hogResult = hog.get(10, TimeUnit.SECONDS);
            assertEquals(500, hogResult.status());
            assertTrue(hogResult.bodyText().contains("Maximum execution time of 2 seconds exceeded"), hogResult.bodyText());
        }
    }

    @Test
    void sleepReleasesTheSlot(@TempDir Path dir) throws Exception {
        Path sleeper = Files.writeString(dir.resolve("sleep.php"), "<?php usleep(1500000); echo 'woke';");
        Path hello = Files.writeString(dir.resolve("hello.php"), "<?php echo 'hi';");
        try (Scheduler scheduler = new Scheduler(1, 10, 0); PhpEngine engine = new PhpEngine(scheduler)) {
            CompletableFuture<PhpEngine.Result> sleeping = CompletableFuture.supplyAsync(() -> run(engine, sleeper));
            Thread.sleep(300);
            long start = System.nanoTime();
            assertEquals("hi", run(engine, hello).bodyText());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 1000, "hello waited " + millis + "ms behind a sleeping request");
            assertEquals("woke", sleeping.get(10, TimeUnit.SECONDS).bodyText());
        }
    }

    private static PhpEngine.Result run(PhpEngine engine, Path script) {
        try {
            return engine.execute(script, new PhpRequest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
