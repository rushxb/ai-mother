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
    private static final Path SUPPORT_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "agent", "GenerationAgentSupport.java"
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

        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("resolveExistingFile("));
        assertTrue(source.contains("readUtf8("));
        for (String forbidden : FORBIDDEN_FILE_SYSTEM_IMPLEMENTATION) {
            assertFalse(source.contains(forbidden),
                    () -> "GeneratedProjectContextService bypasses an existing boundary: " + forbidden);
        }
    }

    @Test
    void contextAgentSupportMustDelegateBoundedFileReads() throws Exception {
        String support = Files.readString(SUPPORT_SOURCE);
        String preparation = Files.readString(PREPARATION_SOURCE);

        assertTrue(support.contains("GeneratedProjectContextService"));
        assertTrue(support.contains("generatedProjectContextService.buildSelectedFileSections("));
        assertFalse(support.contains("FileUtil.readString("));
        assertFalse(support.contains("Files.readString("));
        assertFalse(preparation.contains("GeneratedProjectContextService"));
        assertFalse(preparation.contains("projectContextSupplier"));
    }
}
