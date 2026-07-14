package com.rush.rushaicodemother.orchestration.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEditStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @TempDir
    Path tempDirectory;

    @Test
    void saveMustAtomicallyPersistSnapshotWithoutTemporaryFileLeak() throws Exception {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        LocalEditStateStore store = store(properties);
        EditStateSnapshot snapshot = snapshot("task_1", "src/App.vue");

        assertThat(store.save(1L, snapshot)).isTrue();
        assertThat(store.load(1L)).isEqualTo(snapshot);
        try (var paths = Files.list(tempDirectory)) {
            assertThat(paths.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("app_1.json");
        }
    }

    @Test
    void oversizedSnapshotMustNotOverwriteLastValidState() throws Exception {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setMaxStateFileBytes(16 * 1024);
        LocalEditStateStore store = store(properties);
        EditStateSnapshot validSnapshot = snapshot("task_valid", "src/App.vue");
        assertThat(store.save(1L, validSnapshot)).isTrue();

        List<EditStateSnapshot.RecentFile> oversizedFiles = IntStream.range(0, 400)
                .mapToObj(index -> new EditStateSnapshot.RecentFile(
                        "src/feature/" + index + "-" + "x".repeat(80) + ".vue", NOW.toEpochMilli(), true))
                .toList();
        EditStateSnapshot oversized = new EditStateSnapshot(
                EditStateSnapshot.CURRENT_SCHEMA_VERSION,
                List.of(),
                oversizedFiles,
                List.of(),
                NOW.toEpochMilli());

        assertThat(store.save(1L, oversized)).isFalse();
        assertThat(store.load(1L)).isEqualTo(validSnapshot);
    }

    @Test
    void expiredCorruptAndLegacyFilesMustBeRemovedInsteadOfRepeatedlyParsed() throws Exception {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setStateRetention(Duration.ofHours(24));
        LocalEditStateStore store = store(properties);

        Path expired = store.resolveStatePath(1L);
        Files.createDirectories(expired.getParent());
        Files.writeString(expired, new ObjectMapper().writeValueAsString(snapshot("task_1", "src/App.vue")));
        Files.setLastModifiedTime(expired, FileTime.from(NOW.minus(Duration.ofDays(2))));
        assertThat(store.load(1L).recentFiles()).isEmpty();
        assertThat(expired).doesNotExist();

        Path corrupt = store.resolveStatePath(2L);
        Files.writeString(corrupt, "{not-json");
        assertThat(store.load(2L).recentFiles()).isEmpty();
        assertThat(corrupt).doesNotExist();

        Path legacy = store.resolveStatePath(3L);
        Files.writeString(legacy, "{\"recentEdits\":[{\"taskId\":\"task_legacy\",\"userMessage\":\"secret\"}]}");
        assertThat(store.load(3L).recentFiles()).isEmpty();
        assertThat(legacy).doesNotExist();
    }

    @Test
    void oversizedExistingStateMustBeDeletedBeforeDeserialization() throws Exception {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setMaxStateFileBytes(16 * 1024);
        LocalEditStateStore store = store(properties);
        Path stateFile = store.resolveStatePath(1L);
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, "x".repeat(20 * 1024));

        assertThat(store.load(1L).recentFiles()).isEmpty();
        assertThat(stateFile).doesNotExist();
    }

    @Test
    void persistedAppCountMustRemainBounded() throws Exception {
        EditStatePersistenceProperties properties = properties(tempDirectory);
        properties.setMaxPersistedApps(2);
        LocalEditStateStore store = store(properties);

        assertThat(store.save(1L, snapshot("task_1", "src/One.vue"))).isTrue();
        Thread.sleep(20);
        assertThat(store.save(2L, snapshot("task_2", "src/Two.vue"))).isTrue();
        Thread.sleep(20);
        assertThat(store.save(3L, snapshot("task_3", "src/Three.vue"))).isTrue();

        try (var paths = Files.list(tempDirectory)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".json")).count())
                    .isEqualTo(2);
        }
        assertThat(store.resolveStatePath(1L)).doesNotExist();
        assertThat(store.resolveStatePath(3L)).exists();
    }

    @Test
    void invalidAppIdentityMustNeverResolveAStatePath() {
        LocalEditStateStore store = store(properties(tempDirectory));

        assertThatThrownBy(() -> store.resolveStatePath(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.resolveStatePath(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.resolveStatePath(-1L)).isInstanceOf(IllegalArgumentException.class);
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

    private EditStateSnapshot snapshot(String taskId, String filePath) {
        return new EditStateSnapshot(
                EditStateSnapshot.CURRENT_SCHEMA_VERSION,
                List.of(new EditStateSnapshot.EditRecord(taskId, true, NOW.toEpochMilli())),
                List.of(new EditStateSnapshot.RecentFile(filePath, NOW.toEpochMilli(), true)),
                List.of(new EditStateSnapshot.ValidationRecord(taskId, "success", NOW.toEpochMilli())),
                NOW.toEpochMilli());
    }
}
