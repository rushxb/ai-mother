package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps backend edit validation behind the bounded patch-workspace file-system boundary. */
class BackendValidationFileBoundaryArchitectureTest {

    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "edit", "AgentEditBackendValidationService.java"
    );

    @Test
    void backendValidationMustUseBoundedWorkspaceFileService() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("PatchWorkspaceFileService"));
        assertTrue(source.contains("PatchExecutionProperties"));
        assertFalse(source.contains("Files.readString"));
        assertFalse(source.contains("Files.readAllBytes"));
        assertFalse(source.contains("Files.size"));
        assertFalse(source.contains(".toRealPath("));
        assertFalse(source.contains(".toFile()"));
    }
}
