package com.rush.rushaicodemother.infrastructure.filesystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurePathResolverTest {

    private final SecurePathResolver resolver = new SecurePathResolver();

    private Path tempDirectory;

    @BeforeEach
    void setUp() throws IOException {
        Path testWorkRoot = Path.of(System.getProperty("user.dir"), "target", "test-work", "secure-path-resolver");
        Files.createDirectories(testWorkRoot);
        tempDirectory = Files.createDirectory(testWorkRoot.resolve(UUID.randomUUID().toString()));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDirectory == null || !Files.exists(tempDirectory)) {
            return;
        }
        try (var paths = Files.walk(tempDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void shouldResolveRegularFileInsideScopedDirectory() throws IOException {
        Path root = Files.createDirectory(tempDirectory.resolve("root"));
        Path scope = Files.createDirectory(root.resolve("vue_project_1"));
        Path expectedFile = Files.writeString(scope.resolve("index.html"), "ok");

        Path resolved = resolver.resolveRegularFile(root, "vue_project_1", "index.html");

        assertEquals(expectedFile.toRealPath(), resolved);
    }

    @Test
    void shouldRejectParentTraversalAndAbsolutePaths() throws IOException {
        Path root = Files.createDirectory(tempDirectory.resolve("root"));
        Files.createDirectory(root.resolve("vue_project_1"));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveRegularFile(root, "vue_project_1", "../secret.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveRegularFile(root, "vue_project_1", tempDirectory.resolve("secret.txt").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveRegularFile(root, "../outside", "index.html"));
    }

    @Test
    void shouldRejectSymbolicLinkEscapingScopedDirectory() throws IOException {
        Path root = Files.createDirectory(tempDirectory.resolve("root"));
        Path scope = Files.createDirectory(root.resolve("vue_project_1"));
        Path outsideFile = Files.writeString(tempDirectory.resolve("secret.txt"), "secret");
        Path link = scope.resolve("linked-secret.txt");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false,
                    "The current environment does not permit symbolic-link creation: " + exception.getMessage());
        }

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveRegularFile(root, "vue_project_1", "linked-secret.txt"));
    }
}