package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps generated-project prompt context behind workspace and bounded file-system modules. */
class GenerationProjectContextBoundaryArchitectureTest {

    private static final Path CONTEXT_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "context", "GeneratedProjectContextService.java"
    );
    private static final Path PREPARATION_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "heavy", "HeavyGenerationPreparationService.java"
    );
    private static final List<String> FORBIDDEN_FILE_SYSTEM_IMPLEMENTATION = List.of(
            "CODE_OUTPUT_ROOT_DIR",
            "Files.",
            "FileUtil.",
            "new File(",
            "walkFileTree("
    );

    @Test
    void projectContextModuleMustUseExistingWorkspaceBoundaries() throws Exception {
        String source = Files.readString(CONTEXT_SOURCE);

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("scanProject("));
        assertTrue(source.contains("readUtf8("));
        for (String forbidden : FORBIDDEN_FILE_SYSTEM_IMPLEMENTATION) {
            assertFalse(source.contains(forbidden),
                    () -> "GeneratedProjectContextService bypasses an existing boundary: " + forbidden);
        }
    }

    @Test
    void heavyPreparationMustDelegateProjectContextAssembly() throws Exception {
        String source = Files.readString(PREPARATION_SOURCE);

        assertTrue(source.contains("GeneratedProjectContextService"));
        assertTrue(source.contains("generatedProjectContextService.build("));
        assertFalse(source.contains("GenerationWorkspaceService"));
        assertFalse(source.contains("WorkspaceFileSystemService"));
        assertFalse(source.contains("java.nio.file"));
    }
}
