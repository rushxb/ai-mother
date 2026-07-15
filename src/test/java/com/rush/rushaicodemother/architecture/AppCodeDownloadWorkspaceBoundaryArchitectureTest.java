package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps application code downloads on the canonical generation-workspace boundary. */
class AppCodeDownloadWorkspaceBoundaryArchitectureTest {

    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "application", "app", "AppCodeDownloadApplicationService.java"
    );
    private static final List<String> FORBIDDEN_PATH_IMPLEMENTATION = List.of(
            "CODE_OUTPUT_ROOT_DIR",
            "AppConstant",
            "Path.of(",
            "Files.",
            "new File(",
            "getValue() +"
    );

    @Test
    void downloadModuleMustUseCanonicalGenerationWorkspace() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.resolve("));
        assertTrue(source.contains("workspace.canonicalRootPath()"));
        for (String forbidden : FORBIDDEN_PATH_IMPLEMENTATION) {
            assertFalse(source.contains(forbidden),
                    () -> "AppCodeDownloadApplicationService rebuilds a generated project path: " + forbidden);
        }
    }
}
