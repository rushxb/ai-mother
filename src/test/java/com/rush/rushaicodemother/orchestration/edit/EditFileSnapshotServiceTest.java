package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileSnapshotServiceTest {

    private final EditFileSnapshotService snapshotService = new EditFileSnapshotService();

    @Test
    void restoreRevertsModifiedAndAddedFiles() throws Exception {
        Path tempDir = Path.of("target", "test-workspaces", "edit-file-snapshot", "restore").toAbsolutePath().normalize();
        FileUtil.del(tempDir.toFile());
        Path existingFile = tempDir.resolve("src/App.vue");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "before", StandardCharsets.UTF_8);

        EditFileSnapshotService.EditFileSnapshot snapshot = snapshotService.capture(tempDir, List.of(
                PatchOperation.modify("src/App.vue", "after"),
                PatchOperation.add("src/NewPage.vue", "new")
        ));

        Files.writeString(existingFile, "after", StandardCharsets.UTF_8);
        Path addedFile = tempDir.resolve("src/NewPage.vue");
        Files.writeString(addedFile, "new", StandardCharsets.UTF_8);

        EditFileSnapshotService.RestoreResult result = snapshotService.restore(snapshot);

        assertTrue(result.restored());
        assertEquals("before", Files.readString(existingFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(addedFile));
        assertTrue(result.restoredFiles().contains("src/App.vue"));
        assertTrue(result.restoredFiles().contains("src/NewPage.vue"));
        FileUtil.del(tempDir.toFile());
    }
}
