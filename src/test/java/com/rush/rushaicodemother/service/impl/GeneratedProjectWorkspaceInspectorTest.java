package com.rush.rushaicodemother.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedProjectWorkspaceInspectorTest {

    @Test
    void shouldRejectMissingProjectDirectory() throws Exception {
        Path tempDir = testDir("missing-project");
        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.resolve("missing").toString());

        assertFalse(state.directoryExists());
        assertFalse(state.hasAnyGeneratedFiles());
        assertFalse(state.canAutoRepair());
    }

    @Test
    void shouldRejectEmptyProjectDirectory() throws Exception {
        Path tempDir = testDir("empty-project");
        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.toString());

        assertTrue(state.directoryExists());
        assertFalse(state.hasAnyGeneratedFiles());
        assertFalse(state.canAutoRepair());
    }

    @Test
    void shouldIgnoreOnlyBuildArtifactsWhenCheckingGeneratedFiles() throws Exception {
        Path tempDir = testDir("build-artifacts-only");
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
        Path tempDir = testDir("key-files");
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
        Path tempDir = testDir("partial-generated-files");
        Files.createDirectories(tempDir.resolve("src/components"));
        Files.writeString(tempDir.resolve("src/components/Home.vue"), "<template>Home</template>");
        Files.writeString(tempDir.resolve("src/styles.css"), "body { margin: 0; }");

        GeneratedProjectWorkspaceInspector.WorkspaceState state =
                GeneratedProjectWorkspaceInspector.inspectVueProject(tempDir.toString());

        assertTrue(state.hasAnyGeneratedFiles());
        assertFalse(state.hasKeyProjectFiles());
        assertTrue(state.canAutoRepair());
    }

    private Path testDir(String name) throws Exception {
        Path root = Path.of("target/test-workspaces/generated-project-inspector", name)
                .toAbsolutePath()
                .normalize();
        deleteIfExists(root);
        Files.createDirectories(root);
        return root;
    }

    private void deleteIfExists(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
