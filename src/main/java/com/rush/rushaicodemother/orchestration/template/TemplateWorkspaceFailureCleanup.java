package com.rush.rushaicodemother.orchestration.template;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Removes a workspace owned by a failed template bootstrap operation. */
final class TemplateWorkspaceFailureCleanup {

    private TemplateWorkspaceFailureCleanup() {
    }

    static void deleteOwnedWorkspace(Path workspace, Throwable bootstrapFailure) {
        if (workspace == null || !Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException cleanupFailure) {
            bootstrapFailure.addSuppressed(cleanupFailure);
        }
    }
}
