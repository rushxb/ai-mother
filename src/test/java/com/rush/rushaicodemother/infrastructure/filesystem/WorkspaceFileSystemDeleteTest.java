package com.rush.rushaicodemother.infrastructure.filesystem;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileSystemDeleteTest {

    private Path testRoot;
    private WorkspaceFileSystemService service;

    @BeforeEach
    void setUp() throws IOException {
        testRoot = Path.of("target", "test-work", "workspace-file-delete", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(testRoot);
        service = new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());
    }

    @Test
    void deletesOnlyExistingRegularFileWithinWorkspace() throws IOException {
        Path target = testRoot.resolve("assets/style.css");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "body {}");

        assertTrue(service.deleteFileIfExists(testRoot, "assets/style.css"));
        assertFalse(Files.exists(target));
        assertFalse(service.deleteFileIfExists(testRoot, "assets/style.css"));
    }

    @Test
    void rejectsTraversalAndDirectoryTargets() throws IOException {
        Files.createDirectories(testRoot.resolve("assets"));

        WorkspaceFileSystemException traversal = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.deleteFileIfExists(testRoot, "../outside.txt")
        );
        assertEquals(WorkspaceFileSystemException.Reason.INVALID_PATH, traversal.reason());

        WorkspaceFileSystemException directory = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.deleteFileIfExists(testRoot, "assets")
        );
        assertEquals(WorkspaceFileSystemException.Reason.INVALID_PATH, directory.reason());
    }

    @Test
    void rejectsSymbolicLinkTargetWithoutDeletingItsDestination() throws IOException {
        Path destination = testRoot.resolve("destination.txt");
        Path symbolicLink = testRoot.resolve("linked.txt");
        Files.writeString(destination, "keep-me");
        try {
            Files.createSymbolicLink(symbolicLink, destination);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("当前平台不允许创建符号链接: " + exception.getMessage());
        }

        WorkspaceFileSystemException exception = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.deleteFileIfExists(testRoot, "linked.txt")
        );

        assertEquals(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK, exception.reason());
        assertEquals("keep-me", Files.readString(destination));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (testRoot == null || !Files.exists(testRoot)) {
            return;
        }
        Files.walkFileTree(testRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
