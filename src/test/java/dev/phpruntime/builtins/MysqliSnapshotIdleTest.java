package dev.phpruntime.builtins;

import dev.phpruntime.snapshot.Snapshot;
import dev.phpruntime.snapshot.SnapshotCheck;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A restored link that is never used falls back to cached values and opens no connection. */
@EnabledIfEnvironmentVariable(named = "BLITZ_MYSQL_HOST", matches = ".+")
class MysqliSnapshotIdleTest {

    @Test
    void unusedRestoredLinkDoesNotConnect() throws Exception {
        Path dir = Path.of("src/test/resources/snapshot/mysqli-idle");
        try (Engine engine = SnapshotCheck.newEngine()) {
            Snapshot snapshot = SnapshotCheck.captureSnapshot(engine,
                    dir.resolve("boot.php"), dir.resolve("entry.php"));
            long before = MysqliFunctions.connectionsOpenedForTest();
            String output = SnapshotCheck.restoredOutput(engine,
                    dir.resolve("boot.php"), dir.resolve("entry.php"), snapshot);
            long after = MysqliFunctions.connectionsOpenedForTest();
            assertEquals(before, after, "a restored link used only through cached operations connected");
            assertTrue(output.contains("charset=utf8mb4"), output);
        }
    }
}
