package com.rush.rushaicodemother.orchestration.dag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationOrchestrationTaskStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createMustPersistAnAtomicSnapshotWithoutRawUserMessage() throws Exception {
        GenerationOrchestrationTaskStore store = store(properties(tempDirectory));

        GenerationOrchestrationTask task = store.create(11L, "sensitive user request");

        Path taskFile = store.resolveTaskPath(11L, task.getTaskId());
        String snapshot = Files.readString(taskFile, StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(taskFile));
        assertEquals(64, task.getRequestHash().length());
        assertFalse(snapshot.contains("sensitive user request"));
        assertTrue(snapshot.contains(task.getRequestHash()));
        try (var files = Files.list(taskFile.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void oversizedSnapshotMustRemoveThePreviousSnapshotInsteadOfLeavingStaleState() throws Exception {
        GenerationTaskSnapshotProperties properties = properties(tempDirectory);
        properties.setMaxSnapshotBytes(16 * 1024);
        GenerationOrchestrationTaskStore store = store(properties);
        GenerationOrchestrationTask task = task("task-oversized", 12L);
        store.save(task);
        Path taskFile = store.resolveTaskPath(12L, task.getTaskId());
        assertTrue(Files.exists(taskFile));

        task.setFailureMessage("x".repeat(32 * 1024));
        store.save(task);

        assertFalse(Files.exists(taskFile));
    }

    @Test
    void cleanupMustEnforceRetentionAndPerApplicationCount() throws Exception {
        GenerationTaskSnapshotProperties properties = properties(tempDirectory);
        properties.setMaxSnapshotsPerApp(2);
        properties.setRetention(Duration.ofDays(7));
        GenerationOrchestrationTaskStore store = store(properties);
        Path staleFile = store.resolveTaskPath(13L, "stale-task");
        Files.createDirectories(staleFile.getParent());
        Files.writeString(staleFile, "{}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(Duration.ofDays(8))));

        store.save(task("task-one", 13L));
        store.save(task("task-two", 13L));
        store.save(task("task-three", 13L));

        assertFalse(Files.exists(staleFile));
        try (var files = Files.list(staleFile.getParent())) {
            List<Path> snapshots = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
            assertEquals(2, snapshots.size());
        }
    }

    @Test
    void invalidIdentityMustNotEscapeConfiguredRoot() {
        GenerationOrchestrationTaskStore store = store(properties(tempDirectory));
        GenerationOrchestrationTask task = task("../escape", 14L);

        assertDoesNotThrow(() -> store.save(task));
        assertThrows(IllegalArgumentException.class, () -> store.resolveTaskPath(14L, "../escape"));
        assertFalse(Files.exists(tempDirectory.getParent().resolve("escape.json")));
    }

    @Test
    void storageFailureMustRemainBestEffortForTheGenerationWorkflow() throws Exception {
        Path rootFile = tempDirectory.resolve("not-a-directory");
        Files.writeString(rootFile, "occupied", StandardCharsets.UTF_8);
        GenerationOrchestrationTaskStore store = store(properties(rootFile));

        assertDoesNotThrow(() -> store.save(task("task-storage-failure", 15L)));
    }

    private GenerationOrchestrationTaskStore store(GenerationTaskSnapshotProperties properties) {
        return new GenerationOrchestrationTaskStore(properties);
    }

    private GenerationTaskSnapshotProperties properties(Path rootDirectory) {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setRootDirectory(rootDirectory);
        properties.setRetention(Duration.ofDays(7));
        properties.setMaxSnapshotsPerApp(100);
        properties.setMaxSnapshotBytes(2 * 1024 * 1024);
        properties.setLockStripes(8);
        return properties;
    }

    private GenerationOrchestrationTask task(String taskId, Long appId) {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId(taskId);
        task.setAppId(appId);
        task.setStatus("running");
        return task;
    }
}
