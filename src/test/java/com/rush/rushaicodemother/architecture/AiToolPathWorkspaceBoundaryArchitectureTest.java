package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps AI tool project-root resolution behind the canonical generation-workspace boundary. */
class AiToolPathWorkspaceBoundaryArchitectureTest {

    private static final Path TOOL_PATH_SUPPORT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "ai", "tools",
            "ToolPathSupport.java"
    );

    @Test
    void toolPathSupportMustNotRebuildGeneratedWorkspacePaths() throws Exception {
        String source = Files.readString(TOOL_PATH_SUPPORT);

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.resolve("));
        assertTrue(source.contains("workspace.canonicalRootPath()"));
        assertFalse(source.contains("AppConstant"));
        assertFalse(source.contains("CODE_OUTPUT_ROOT_DIR"));
        assertFalse(source.contains("codeGenType.getValue() +"));
    }
}
