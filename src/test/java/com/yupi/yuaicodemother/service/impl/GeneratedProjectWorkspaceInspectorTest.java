package com.yupi.yuaicodemother.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedProjectWorkspaceInspectorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectMissingProjectDirectory() {
        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.resolve("missing").toString());

        assertFalse(state.directoryExists());
        assertFalse(state.hasAnyGeneratedFiles());
        assertFalse(state.canAutoRepair());
    }

    @Test
    void shouldRejectEmptyProjectDirectory() {
        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.toString());

        assertTrue(state.directoryExists());
        assertFalse(state.hasAnyGeneratedFiles());
        assertFalse(state.canAutoRepair());
    }

    @Test
    void shouldIgnoreOnlyBuildArtifactsWhenCheckingGeneratedFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("node_modules/.bin"));
        Files.writeString(tempDir.resolve("node_modules/.bin/vite"), "");
        Files.writeString(tempDir.resolve(".ai-code-install.stamp"), "abc");

        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.toString());

        assertFalse(state.hasAnyGeneratedFiles());
        assertFalse(state.canAutoRepair());
    }

    @Test
    void shouldAllowAutoRepairWhenKeyVueProjectFilesExist() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("package.json"), "{\"scripts\":{\"build\":\"vite\"}}");
        Files.writeString(tempDir.resolve("index.html"), "<div id=\"app\"></div>");
        Files.writeString(tempDir.resolve("src/main.ts"), "import { createApp } from 'vue'");

        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.toString());

        assertTrue(state.hasAnyGeneratedFiles());
        assertTrue(state.hasKeyProjectFiles());
        assertTrue(state.canAutoRepair());
    }

    @Test
    void shouldAllowAutoRepairForPartialGeneratedFilesWithoutKnownKeyFile() throws Exception {
        Files.createDirectories(tempDir.resolve("src/components"));
        Files.writeString(tempDir.resolve("src/components/Home.vue"), "<template>Home</template>");
        Files.writeString(tempDir.resolve("src/styles.css"), "body { margin: 0; }");

        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.toString());

        assertTrue(state.hasAnyGeneratedFiles());
        assertFalse(state.hasKeyProjectFiles());
        assertTrue(state.canAutoRepair());
    }
}
