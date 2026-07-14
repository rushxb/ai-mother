package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps online application-code access behind the bounded workspace file-system module. */
class AppCodeWorkspaceFileBoundaryArchitectureTest {

    private static final Path SOURCE_FILE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "rush",
            "rushaicodemother",
            "service",
            "workspace",
            "LocalAppCodeWorkspaceService.java"
    );
    private static final List<String> FORBIDDEN_ACCESS = List.of(
            "Files.",
            "FileUtil.",
            "AtomicMoveNotSupportedException",
            "StandardCopyOption",
            "StandardOpenOption",
            "MAX_EDIT_FILE_SIZE",
            "MAX_FILE_TREE_DEPTH",
            "writeUtf8Atomically("
    );

    @Test
    void localWorkspaceServiceMustUseBoundedFileSystemBoundary() throws IOException {
        String source = Files.readString(SOURCE_FILE);

        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("WorkspaceFileSystemProperties"));
        assertTrue(source.contains("replaceUtf8Atomically("));
        assertTrue(source.contains("listTree("));
        for (String forbiddenAccess : FORBIDDEN_ACCESS) {
            assertFalse(source.contains(forbiddenAccess),
                    () -> "LocalAppCodeWorkspaceService bypasses file-system boundary: " + forbiddenAccess);
        }
    }
}