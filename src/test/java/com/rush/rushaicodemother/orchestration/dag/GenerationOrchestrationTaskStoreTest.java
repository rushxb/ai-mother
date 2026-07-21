package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void externallyReservedTaskIdMustBePreservedAndValidated() {
        GenerationOrchestrationTaskStore store = store(properties(tempDirectory));

        GenerationOrchestrationTask task = store.create("runtime-task_11", 11L, "request");

        assertEquals("runtime-task_11", task.getTaskId());
        assertThrows(IllegalArgumentException.class,
                () -> store.create("../escape", 11L, "request"));
    }

    @Test
    void loadMustRestoreAVersionedCheckpointAndVerifyTheRequestHash() {
        GenerationOrchestrationTaskStore store = store(properties(tempDirectory));
        GenerationOrchestrationTask task = store.create("runtime-task-resume", 11L, "original request");
        task.setRuntimeState(AgentRuntimeState.RUNNING);
        task.setLastCompletedNode("planner");
        task.setCheckpointVersion(1);
        task.getNodeStatuses().put("planner", "done");
        task.getTimings().put("planner", 25L);
        task.getArtifacts().put("requirements", com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact.of(
                "requirements", "Planner", "requirements", Map.of("targetType", "vue_project")
        ));
        store.save(task);

        GenerationOrchestrationTask restored = store.load(11L, "runtime-task-resume").orElseThrow();

        assertEquals(GenerationOrchestrationTask.CURRENT_SCHEMA_VERSION, restored.getSchemaVersion());
        assertEquals(AgentRuntimeState.RUNNING, restored.getRuntimeState());
        assertEquals("done", restored.getNodeStatuses().get("planner"));
        assertEquals(25L, restored.getTimings().get("planner"));
        assertTrue(store.matchesRequest(restored, "original request"));
        assertFalse(store.matchesRequest(restored, "different request"));
    }

    @Test
    void durableRepositoryMustStoreAndLoadCheckpointsWithoutLocalFiles() {
        GenerationOrchestrationCheckpointRepository repository =
                mock(GenerationOrchestrationCheckpointRepository.class);
        GenerationOrchestrationTaskStore store = durableStore(properties(tempDirectory), repository);

        GenerationOrchestrationTask task = store.create("runtime-task-db", 11L, "original request");
        String payload = cn.hutool.json.JSONUtil.toJsonPrettyStr(task);
        when(repository.loadPayload(11L, "runtime-task-db")).thenReturn(java.util.Optional.of(payload));

        GenerationOrchestrationTask restored = store.load(11L, "runtime-task-db").orElseThrow();

        verify(repository).save(eq(task), any(String.class), any(Integer.class));
        assertFalse(Files.exists(store.resolveTaskPath(11L, "runtime-task-db")));
        assertEquals("runtime-task-db", restored.getTaskId());
        assertTrue(store.matchesRequest(restored, "original request"));
    }

    @Test
    void oversizedDurableCheckpointMustDeletePreviousRepositoryPayload() {
        GenerationTaskSnapshotProperties properties = properties(tempDirectory);
        properties.setMaxSnapshotBytes(128);
        GenerationOrchestrationCheckpointRepository repository =
                mock(GenerationOrchestrationCheckpointRepository.class);
        GenerationOrchestrationTaskStore store = durableStore(properties, repository);
        GenerationOrchestrationTask task = task("task-oversized-db", 12L);
        task.setExecutionEpoch(3L);
        task.setRequestHash("a".repeat(64));
        task.setFailureMessage("x".repeat(1024));

        GenerationCheckpointPersistenceException failure = assertThrows(
                GenerationCheckpointPersistenceException.class,
                () -> store.save(task));

        assertEquals(GenerationCheckpointPersistenceException.Reason.SNAPSHOT_TOO_LARGE,
                failure.reason());
        verify(repository, never()).delete(12L, "task-oversized-db", 3L);
        verify(repository, never()).save(any(), any(), any(Integer.class));
    }

    @Test
    void corruptCheckpointMustFailClosedInsteadOfSilentlyStartingOver() throws Exception {
        GenerationOrchestrationTaskStore store = store(properties(tempDirectory));
        Path taskFile = store.resolveTaskPath(11L, "runtime-task-corrupt");
        Files.createDirectories(taskFile.getParent());
        Files.writeString(taskFile, "not-json", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> store.load(11L, "runtime-task-corrupt"));
    }

    @Test
    void legacyCreateMustDelegateIdentityGenerationToConfiguredStrategy() {
        GenerationTaskIdGenerator taskIdGenerator = () -> "generated-task-12";
        GenerationOrchestrationTaskStore store = new GenerationOrchestrationTaskStore(
                properties(tempDirectory), taskIdGenerator);

        GenerationOrchestrationTask task = store.create(12L, "request");

        assertEquals("generated-task-12", task.getTaskId());
    }

    @Test
    void oversizedSnapshotMustFailClosedAndPreserveTheLastDurableSnapshot() throws Exception {
        GenerationTaskSnapshotProperties properties = properties(tempDirectory);
        properties.setMaxSnapshotBytes(16 * 1024);
        GenerationOrchestrationTaskStore store = store(properties);
        GenerationOrchestrationTask task = task("task-oversized", 12L);
        store.save(task);
        Path taskFile = store.resolveTaskPath(12L, task.getTaskId());
        assertTrue(Files.exists(taskFile));

        task.setFailureMessage("x".repeat(32 * 1024));
        assertThrows(GenerationCheckpointPersistenceException.class, () -> store.save(task));

        assertTrue(Files.exists(taskFile));
        assertFalse(Files.readString(taskFile, StandardCharsets.UTF_8).contains("x".repeat(1024)));
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

        GenerationCheckpointPersistenceException failure = assertThrows(
                GenerationCheckpointPersistenceException.class,
                () -> store.save(task));
        assertEquals(GenerationCheckpointPersistenceException.Reason.INVALID_IDENTITY,
                failure.reason());
        assertThrows(IllegalArgumentException.class, () -> store.resolveTaskPath(14L, "../escape"));
        assertFalse(Files.exists(tempDirectory.getParent().resolve("escape.json")));
    }

    @Test
    void storageFailureMustStopTheGenerationWorkflow() throws Exception {
        Path rootFile = tempDirectory.resolve("not-a-directory");
        Files.writeString(rootFile, "occupied", StandardCharsets.UTF_8);
        GenerationOrchestrationTaskStore store = store(properties(rootFile));

        GenerationCheckpointPersistenceException failure = assertThrows(
                GenerationCheckpointPersistenceException.class,
                () -> store.save(task("task-storage-failure", 15L)));

        assertEquals(GenerationCheckpointPersistenceException.Reason.STORAGE_FAILURE,
                failure.reason());
    }

    private GenerationOrchestrationTaskStore store(GenerationTaskSnapshotProperties properties) {
        return new GenerationOrchestrationTaskStore(properties);
    }

    private GenerationOrchestrationTaskStore durableStore(GenerationTaskSnapshotProperties properties,
                                                         GenerationOrchestrationCheckpointRepository repository) {
        return new GenerationOrchestrationTaskStore(properties, () -> "generated-task", repository);
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
