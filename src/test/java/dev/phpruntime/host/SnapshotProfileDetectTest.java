package dev.phpruntime.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnapshotProfileDetectTest {

    @Test
    void recognisesAWordPressRoot(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("wp-load.php"), "<?php\n");
        Files.writeString(root.resolve("wp-settings.php"), "<?php\n");
        Files.createDirectories(root.resolve("wp-includes"));
        Files.writeString(root.resolve("wp-includes").resolve("version.php"), "<?php\n");
        assertEquals("wordpress", SnapshotProfile.detect(root));
    }

    @Test
    void needsAllThreeWordPressFiles(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("wp-load.php"), "<?php\n");
        Files.writeString(root.resolve("wp-settings.php"), "<?php\n");
        assertNull(SnapshotProfile.detect(root));
    }

    @Test
    void ignoresOtherRoots(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("index.php"), "<?php echo 1;\n");
        assertNull(SnapshotProfile.detect(root));
        assertNull(SnapshotProfile.detect(null));
    }
}
