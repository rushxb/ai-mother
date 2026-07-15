package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps artifact lifecycle orchestration behind the bounded directory-copy boundary. */
class ArtifactCopyBoundaryArchitectureTest {

    private static final Path LIFECYCLE_SERVICE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "rush",
            "rushaicodemother",
            "service",
            "artifact",
            "LocalAppArtifactLifecycleService.java"
    );
    private static final List<String> FORBIDDEN_COPY_IMPLEMENTATION = List.of(
            "RobocopyDirectoryCopier",
            "Files.copy(",
            "Files.move(",
            "Files.readSymbolicLink(",
            "copyDirectoryWithNio",
            "copyDirectoryWithRobocopy",
            "validateInternalSymbolicLinks",
            "GENERATED_DIRECTORY_EXCLUSIONS",
            "GENERATED_FILE_EXCLUSIONS",
            "enum CopyProfile",
            "boolean windows"
    );

    @Test
    void localLifecycleServiceMustUseArtifactDirectoryCopier() throws IOException {
        String source = Files.readString(LIFECYCLE_SERVICE);

        assertTrue(source.contains("ArtifactDirectoryCopier"));
        assertTrue(source.contains("artifactDirectoryCopier.copy("));
        assertTrue(source.contains("ArtifactPathMover"));
        assertTrue(source.contains("artifactPathMover.move("));
        assertTrue(source.contains("catch (InterruptedException exception)"));
        assertTrue(source.contains("Thread.currentThread().interrupt()"));
        for (String forbidden : FORBIDDEN_COPY_IMPLEMENTATION) {
            assertFalse(
                    source.contains(forbidden),
                    () -> "LocalAppArtifactLifecycleService bypasses artifact copy boundary: " + forbidden
            );
        }
    }

    @Test
    void artifactPathMoverMustOwnAtomicPublicationAndBoundedAccessDeniedRetry() throws IOException {
        Path moverPath = LIFECYCLE_SERVICE.resolveSibling("ArtifactPathMover.java");
        String source = Files.readString(moverPath);

        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertTrue(source.contains("AtomicMoveNotSupportedException"));
        assertTrue(source.contains("AccessDeniedException"));
        assertTrue(source.contains("getPublishMaxAttempts()"));
        assertTrue(source.contains("getPublishRetryDelayMillis()"));
        assertFalse(source.contains("StandardCopyOption.REPLACE_EXISTING"));
    }
}
