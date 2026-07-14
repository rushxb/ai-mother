package com.rush.rushaicodemother.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VueBuildStateStoreTest {

    @TempDir
    Path projectRoot;

    private final VueBuildStateStore stateStore = new VueBuildStateStore();

    @Test
    void shouldPersistCompleteStateAndRemoveLegacyStampFiles() throws Exception {
        Files.writeString(projectRoot.resolve(".ai-code-install.stamp"), "legacy", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve(".ai-code-critical.stamp"), "legacy", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve(".ai-code-presentation.stamp"), "legacy", StandardCharsets.UTF_8);
        VueProjectSnapshot snapshot = new VueProjectSnapshot("dependency", "critical", "presentation");

        stateStore.persist(projectRoot, snapshot);

        assertEquals(new VueBuildState("dependency", "critical", "presentation"), stateStore.read(projectRoot));
        assertFalse(Files.exists(projectRoot.resolve(".ai-code-install.stamp")));
        assertFalse(Files.exists(projectRoot.resolve(".ai-code-critical.stamp")));
        assertFalse(Files.exists(projectRoot.resolve(".ai-code-presentation.stamp")));
    }

    @Test
    void shouldPreserveSourceFingerprintsWhenOnlyDependencyInstallCompletes() throws Exception {
        stateStore.persist(projectRoot, new VueProjectSnapshot("old", "critical", "presentation"));

        stateStore.recordDependencyInstalled(projectRoot, "new");

        assertEquals(new VueBuildState("new", "critical", "presentation"), stateStore.read(projectRoot));
    }

    @Test
    void shouldTreatCorruptedStateAsCacheMiss() throws Exception {
        Files.writeString(
                projectRoot.resolve(VueBuildStateStore.STATE_FILE_NAME),
                "{not-json",
                StandardCharsets.UTF_8
        );

        assertEquals(VueBuildState.empty(), stateStore.read(projectRoot));
    }
}
