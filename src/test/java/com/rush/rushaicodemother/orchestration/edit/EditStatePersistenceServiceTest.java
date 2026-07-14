package com.rush.rushaicodemother.orchestration.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EditStatePersistenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @TempDir
    Path tempDirectory;

    @Test
    void sameAppConcurrentEditAndValidationUpdatesMustNotLoseState() throws Exception {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setMaxRecentEdits(50);
        properties.setMaxRecentFiles(50);
        properties.setMaxRecentValidations(50);
        LocalEditStateStore store = store(properties);
        EditStatePersistenceService service = service(properties, store);
        int updateCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> operations = new ArrayList<>();
        for (int index = 0; index < updateCount; index++) {
            int taskIndex = index;
            operations.add(() -> {
                await(start);
                service.recordEditResult(1L, "edit_" + taskIndex,
                        List.of(operation("src/File" + taskIndex + ".vue")), true);
            });
            operations.add(() -> {
                await(start);
                service.recordValidationResult(1L, "validation_" + taskIndex, "success");
            });
        }
        operations.forEach(executor::submit);
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        EditStateSnapshot persisted = store.load(1L);
        assertThat(persisted.recentEdits()).hasSize(updateCount);
        assertThat(persisted.recentValidations()).hasSize(updateCount);
        assertThat(persisted.recentFiles()).hasSize(updateCount);
        assertThat(persisted.recentFiles().stream().map(EditStateSnapshot.RecentFile::filePath).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(IntStream.range(0, updateCount)
                        .mapToObj(index -> "src/File" + index + ".vue")
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void persistedStateMustBeBoundedAndExcludeRawDiagnosticText() throws Exception {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setMaxRecentEdits(2);
        properties.setMaxRecentFiles(3);
        properties.setMaxRecentValidations(2);
        LocalEditStateStore store = store(properties);
        EditStatePersistenceService service = service(properties, store);

        for (int index = 0; index < 5; index++) {
            service.recordEditResult(1L, "task_" + index,
                    List.of(operation("src/File" + index + ".vue")), true);
            service.recordValidationResult(1L, "task_" + index, "failed");
        }
        service.recordEditResult(1L, "task_null", null, false);

        EditStateSnapshot snapshot = store.load(1L);
        assertThat(snapshot.recentEdits()).hasSize(2);
        assertThat(snapshot.recentFiles()).hasSize(3);
        assertThat(snapshot.recentValidations()).hasSize(2);
        String persistedJson = Files.readString(store.resolveStatePath(1L));
        assertThat(persistedJson)
                .doesNotContain("userMessage")
                .doesNotContain("reason")
                .doesNotContain("message")
                .doesNotContain("details");
    }

    @Test
    void queriesMustValidateBoundariesAndRecallOnlySuccessfulNormalizedPaths() {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        LocalEditStateStore store = store(properties);
        EditStatePersistenceService service = service(properties, store);

        service.recordEditResult(1L, "task_ok", java.util.Arrays.asList(
                operation("src\\pages\\HomePage.vue"),
                operation("../outside.txt"),
                null), true);
        assertThat(service.getRecentModifiedFiles(1L, 10))
                .containsExactly("src/pages/HomePage.vue");
        assertThat(service.getRelevantRecentFiles(1L, "请修改 HomePage 页面", 10))
                .containsExactly("src/pages/HomePage.vue");
        assertThat(service.wasRecentlyModified(1L, "src/pages/HomePage.vue", 1)).isTrue();

        service.recordEditResult(1L, "task_failed", List.of(operation("src/pages/HomePage.vue")), false);
        assertThat(service.getRecentModifiedFiles(1L, 10)).isEmpty();
        assertThat(service.wasRecentlyModified(1L, "src/pages/HomePage.vue", 1)).isFalse();

        assertThat(service.getRecentModifiedFiles(null, 10)).isEmpty();
        assertThat(service.getRecentModifiedFiles(0L, 10)).isEmpty();
        assertThat(service.getRecentModifiedFiles(1L, 0)).isEmpty();
        assertThat(service.getRelevantRecentFiles(1L, " ", 10)).isEmpty();
        assertThat(service.wasRecentlyModified(1L, "src/pages/HomePage.vue", 0)).isFalse();
    }

    @Test
    void cacheMustRespectConfiguredMaximumSize() {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setEnabled(false);
        properties.setMaxCacheEntries(2);
        EditStatePersistenceService service = service(properties, store(properties));

        service.recordEditResult(1L, "task_1", List.of(operation("src/One.vue")), true);
        service.recordEditResult(2L, "task_2", List.of(operation("src/Two.vue")), true);
        service.recordEditResult(3L, "task_3", List.of(operation("src/Three.vue")), true);

        assertThat(service.estimatedCacheSize()).isLessThanOrEqualTo(2);
    }

    @Test
    void localStorageFailureMustNotInterruptEditRecall() throws Exception {
        Path blockingParent = tempDirectory.resolve("blocking-file");
        Files.writeString(blockingParent, "not-a-directory");
        EditStatePersistenceProperties properties = properties(blockingParent.resolve("edit-state"));
        LocalEditStateStore store = store(properties);
        EditStatePersistenceService service = service(properties, store);

        service.recordEditResult(1L, "task_1", List.of(operation("src/App.vue")), true);

        assertThat(service.getRecentModifiedFiles(1L, 10)).containsExactly("src/App.vue");
        assertThat(store.resolveStatePath(1L)).doesNotExist();
    }

    @Test
    void expiredCacheConfigurationStillUsesRetentionAsQueryBoundary() {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setStateRetention(Duration.ofHours(1));
        properties.setCacheExpireAfterAccess(Duration.ofMinutes(30));
        LocalEditStateStore store = store(properties);
        EditStatePersistenceService service = service(properties, store);

        service.recordEditResult(1L, "task_1", List.of(operation("src/App.vue")), true);

        assertThat(service.wasRecentlyModified(1L, "src/App.vue", Long.MAX_VALUE)).isTrue();
    }

    private EditStatePersistenceService service(EditStatePersistenceProperties properties,
                                                LocalEditStateStore store) {
        return new EditStatePersistenceService(
                properties,
                store,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private LocalEditStateStore store(EditStatePersistenceProperties properties) {
        return new LocalEditStateStore(
                properties,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EditStatePersistenceProperties properties(Path rootDirectory) {
        EditStatePersistenceProperties properties = new EditStatePersistenceProperties();
        properties.setRootDirectory(rootDirectory);
        return properties;
    }

    private PatchOperation operation(String path) {
        return new PatchOperation("modify", path, "", "before", "after");
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
