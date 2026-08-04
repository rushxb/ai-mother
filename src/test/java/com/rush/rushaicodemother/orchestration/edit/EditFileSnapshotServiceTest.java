package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceException;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EditFileSnapshotServiceTest {

    @Test
    void restoreRevertsModifiedAndAddedFiles() throws Exception {
        Path tempDir = workspace("restore");
        Path existingFile = tempDir.resolve("src/App.vue");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "before", StandardCharsets.UTF_8);
        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        EditFileSnapshotService snapshotService = service(
                new PatchExecutionProperties(), fenceGuard);

        EditFileSnapshotService.EditFileSnapshot snapshot = snapshotService.capture(tempDir, List.of(
                PatchOperation.modify("src/App.vue", "after"),
                PatchOperation.add("src/NewPage.vue", "new")
        ));

        Files.writeString(existingFile, "after", StandardCharsets.UTF_8);
        Path addedFile = tempDir.resolve("src/NewPage.vue");
        Files.writeString(addedFile, "new", StandardCharsets.UTF_8);

        EditFileSnapshotService.RestoreResult result = snapshotService.restore("task-1", snapshot);

        assertTrue(result.restored());
        assertEquals("before", Files.readString(existingFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(addedFile));
        assertTrue(result.restoredFiles().contains("src/App.vue"));
        assertTrue(result.restoredFiles().contains("src/NewPage.vue"));
        verify(fenceGuard).assertCurrent("task-1");
        FileUtil.del(tempDir.toFile());
    }

    @Test
    void uncommittedTransactionShouldRollbackAutomaticallyOnClose() throws Exception {
        Path tempDir = workspace("transaction-auto-rollback");
        Path target = tempDir.resolve("src/App.vue");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before", StandardCharsets.UTF_8);
        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        EditFileSnapshotService snapshotService = service(
                new PatchExecutionProperties(), fenceGuard);

        try (EditWorkspaceTransaction transaction = snapshotService.beginTransaction(
                "task-transaction", tempDir,
                List.of(PatchOperation.modify("src/App.vue", "after")))) {
            Files.writeString(target, "after", StandardCharsets.UTF_8);
        }

        assertEquals("before", Files.readString(target, StandardCharsets.UTF_8));
        verify(fenceGuard).assertCurrent("task-transaction");
        FileUtil.del(tempDir.toFile());
    }

    @Test
    void committedTransactionShouldKeepValidatedChanges() throws Exception {
        Path tempDir = workspace("transaction-commit");
        Path target = tempDir.resolve("src/App.vue");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before", StandardCharsets.UTF_8);
        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        EditFileSnapshotService snapshotService = service(
                new PatchExecutionProperties(), fenceGuard);

        try (EditWorkspaceTransaction transaction = snapshotService.beginTransaction(
                "task-commit", tempDir,
                List.of(PatchOperation.modify("src/App.vue", "after")))) {
            Files.writeString(target, "after", StandardCharsets.UTF_8);
            transaction.commit();
            transaction.commit();
            assertEquals(EditWorkspaceTransaction.State.COMMITTED, transaction.state());
        }

        assertEquals("after", Files.readString(target, StandardCharsets.UTF_8));
        FileUtil.del(tempDir.toFile());
    }

    @Test
    void rollbackShouldCoverFilesAddedByLaterRepairRounds() throws Exception {
        Path tempDir = workspace("transaction-repair-round");
        Path original = tempDir.resolve("src/App.vue");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "before", StandardCharsets.UTF_8);
        EditFileSnapshotService snapshotService = service(new PatchExecutionProperties());

        try (EditWorkspaceTransaction transaction = snapshotService.beginTransaction(
                "task-repair", tempDir,
                List.of(PatchOperation.modify("src/App.vue", "after")))) {
            transaction.include(List.of(PatchOperation.add("src/Repair.vue", "repair")));
            Files.writeString(original, "after", StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("src/Repair.vue"), "repair", StandardCharsets.UTF_8);

            EditFileSnapshotService.RestoreResult first = transaction.rollback();
            EditFileSnapshotService.RestoreResult second = transaction.rollback();

            assertEquals(first, second);
            assertEquals(EditWorkspaceTransaction.State.ROLLED_BACK, transaction.state());
            assertThrows(IllegalStateException.class, transaction::commit);
        }

        assertEquals("before", Files.readString(original, StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempDir.resolve("src/Repair.vue")));
        FileUtil.del(tempDir.toFile());
    }

    @Test
    void restoreFailureMustUseStableReason() throws Exception {
        Path tempDir = workspace("restore-failure");
        Path target = tempDir.resolve("blocked/secret-file.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before", StandardCharsets.UTF_8);
        EditFileSnapshotService snapshotService = service(new PatchExecutionProperties());
        EditFileSnapshotService.EditFileSnapshot snapshot = snapshotService.capture(
                tempDir, List.of(PatchOperation.modify("blocked/secret-file.txt", "after")));

        Files.delete(target);
        Files.delete(target.getParent());
        Files.writeString(tempDir.resolve("blocked"), "not-a-directory", StandardCharsets.UTF_8);

        EditFileSnapshotService.RestoreResult result = snapshotService.restore(snapshot);

        assertEquals("failed", result.status());
        assertEquals(List.of("blocked/secret-file.txt:restore_failed"), result.failedFiles());
        FileUtil.del(tempDir.toFile());
    }

    @Test
    void captureRejectsTraversalInsteadOfSilentlySkippingIt() throws Exception {
        Path tempDir = workspace("traversal");
        EditFileSnapshotService snapshotService = service(new PatchExecutionProperties());

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> snapshotService.capture(tempDir, List.of(
                        PatchOperation.modify("src/../outside.txt", "unsafe")))
        );

        assertEquals("path_outside_project", exception.reason());
        FileUtil.del(tempDir.toFile());
    }

    @Test
    void captureMissingEnforcesTotalSnapshotBudget() throws Exception {
        Path tempDir = workspace("budget");
        Files.writeString(tempDir.resolve("first.txt"), "a".repeat(700), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("second.txt"), "b".repeat(700), StandardCharsets.UTF_8);
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxRollbackSnapshotBytes(1_024);
        EditFileSnapshotService snapshotService = service(properties);
        EditFileSnapshotService.EditFileSnapshot snapshot = snapshotService.capture(
                tempDir, List.of(PatchOperation.modify("first.txt", "changed")));

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> snapshotService.captureMissing(
                        snapshot, List.of(PatchOperation.modify("second.txt", "changed")))
        );

        assertEquals("rollback_snapshot_limit_exceeded", exception.reason());
        assertEquals(700, snapshot.capturedBytes());
        FileUtil.del(tempDir.toFile());
    }

    @Test
    void restoreRejectsSymbolicLinkReplacementWithoutTouchingExternalFile() throws Exception {
        Path tempDir = workspace("symbolic-link");
        Path target = tempDir.resolve("src/App.vue");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before", StandardCharsets.UTF_8);
        EditFileSnapshotService snapshotService = service(new PatchExecutionProperties());
        EditFileSnapshotService.EditFileSnapshot snapshot = snapshotService.capture(
                tempDir, List.of(PatchOperation.modify("src/App.vue", "after")));
        Path externalFile = Files.createTempFile("edit-snapshot-external", ".vue");
        Files.writeString(externalFile, "external", StandardCharsets.UTF_8);
        Files.delete(target);
        createSymbolicLinkOrSkip(target, externalFile);

        EditFileSnapshotService.RestoreResult result = snapshotService.restore(snapshot);

        assertEquals("failed", result.status());
        assertEquals("external", Files.readString(externalFile, StandardCharsets.UTF_8));
        FileUtil.del(tempDir.toFile());
        Files.deleteIfExists(externalFile);
    }

    private EditFileSnapshotService service(PatchExecutionProperties properties) {
        return service(properties, mock(GenerationTaskFenceGuard.class));
    }

    private EditFileSnapshotService service(PatchExecutionProperties properties,
                                            GenerationTaskFenceGuard fenceGuard) {
        return new EditFileSnapshotService(
                new PatchWorkspaceFileService(properties),
                properties,
                fenceGuard);
    }

    private Path workspace(String name) throws IOException {
        Path path = Path.of("target", "test-workspaces", "edit-file-snapshot", name)
                .toAbsolutePath()
                .normalize();
        FileUtil.del(path.toFile());
        Files.createDirectories(path);
        return path;
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
