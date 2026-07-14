package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编辑状态本地持久化的隐私、并发、原子写入和容量门禁。 */
class EditStatePersistenceArchitectureTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path EDIT_SOURCE_ROOT = PROJECT_ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration", "edit"
    ));

    @Test
    void editStateStoreMustRemainAtomicBoundedAndIndependentFromUserHome() throws IOException {
        String store = Files.readString(EDIT_SOURCE_ROOT.resolve("LocalEditStateStore.java"));
        String service = Files.readString(EDIT_SOURCE_ROOT.resolve("EditStatePersistenceService.java"));

        assertFalse(store.contains("Files.writeString"));
        assertFalse(store.contains("Files.readString"));
        assertFalse(store.contains("System.getProperty(\"user.home\")"));
        assertFalse(service.contains("ConcurrentHashMap"));
        assertTrue(service.contains("Caffeine.newBuilder()"));
        assertTrue(service.contains("ReentrantLock[]"));
        assertTrue(store.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertTrue(store.contains("channel.force(true)"));
        assertTrue(store.contains("LinkOption.NOFOLLOW_LINKS"));
        assertTrue(store.contains("getMaxStateFileBytes()"));
        assertTrue(store.contains("getMaxPersistedApps()"));
        assertTrue(store.contains("getStateRetention()"));
    }

    @Test
    void persistedSchemaMustExcludeRawMessagesAndConfigurationMustRemainValidated() throws IOException {
        String snapshot = Files.readString(EDIT_SOURCE_ROOT.resolve("EditStateSnapshot.java"));
        String properties = Files.readString(EDIT_SOURCE_ROOT.resolve("EditStatePersistenceProperties.java"));
        String yaml = Files.readString(PROJECT_ROOT.resolve(Path.of("src", "main", "resources", "application.yml")));

        assertFalse(snapshot.contains("userMessage"));
        assertFalse(snapshot.contains("reason"));
        assertFalse(snapshot.contains("message"));
        assertFalse(snapshot.contains("details"));
        assertTrue(snapshot.contains("CURRENT_SCHEMA_VERSION"));
        assertTrue(properties.contains("@ConfigurationProperties(prefix = \"app.edit-state\")"));
        assertTrue(properties.contains("@Validated"));
        assertTrue(properties.contains("@Min(MIN_STATE_FILE_BYTES)"));
        assertTrue(properties.contains("@Max(MAX_STATE_FILE_BYTES)"));

        assertTrue(yaml.contains("EDIT_STATE_ROOT_DIRECTORY"));
        assertTrue(yaml.contains("EDIT_STATE_MAX_CACHE_ENTRIES"));
        assertTrue(yaml.contains("EDIT_STATE_RETENTION"));
        assertTrue(yaml.contains("EDIT_STATE_MAX_PERSISTED_APPS"));
        assertTrue(yaml.contains("EDIT_STATE_MAX_FILE_BYTES"));
        assertTrue(yaml.contains("EDIT_STATE_LOCK_STRIPES"));
    }
}
