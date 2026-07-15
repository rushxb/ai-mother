package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ArtifactDirectoryCopierTest {

    private Path tempDirectory;
    private Path sourceDirectory;
    private Path targetDirectory;
    private ArtifactLifecycleProperties properties;
    private RobocopyDirectoryCopier robocopyDirectoryCopier;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = createTestDirectory("artifact-directory-copier");
        sourceDirectory = Files.createDirectory(tempDirectory.resolve("source"));
        targetDirectory = tempDirectory.resolve("target");
        properties = new ArtifactLifecycleProperties();
        robocopyDirectoryCopier = mock(RobocopyDirectoryCopier.class);
    }

    @Test
    void shouldExcludeDerivedGeneratedArtifacts() throws Exception {
        Files.createDirectories(sourceDirectory.resolve("src/components"));
        Files.writeString(sourceDirectory.resolve("src/App.vue"), "app", StandardCharsets.UTF_8);
        Files.writeString(sourceDirectory.resolve("src/components/Button.vue"), "button", StandardCharsets.UTF_8);
        Files.createDirectories(sourceDirectory.resolve("node_modules/package"));
        Files.writeString(sourceDirectory.resolve("node_modules/package/index.js"), "derived", StandardCharsets.UTF_8);
        Files.createDirectories(sourceDirectory.resolve("dist"));
        Files.writeString(sourceDirectory.resolve("dist/index.html"), "derived", StandardCharsets.UTF_8);
        Files.writeString(sourceDirectory.resolve(".ai-code-install.stamp"), "stamp", StandardCharsets.UTF_8);

        newCopier(false).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.GENERATED_SOURCE);

        assertTrue(Files.isRegularFile(targetDirectory.resolve("src/App.vue")));
        assertTrue(Files.isRegularFile(targetDirectory.resolve("src/components/Button.vue")));
        assertFalse(Files.exists(targetDirectory.resolve("node_modules")));
        assertFalse(Files.exists(targetDirectory.resolve("dist")));
        assertFalse(Files.exists(targetDirectory.resolve(".ai-code-install.stamp")));
    }

    @Test
    void shouldRejectTargetNestedInsideSourceDirectory() {
        Path nestedTarget = sourceDirectory.resolve("nested-target");

        assertCopyFailure(
                ArtifactCopyException.Reason.INVALID_PATH,
                () -> newCopier(false).copy(sourceDirectory, nestedTarget, ArtifactCopyProfile.DEPLOYMENT)
        );
        assertFalse(Files.exists(nestedTarget));
    }
    @Test
    void shouldRejectFileCountAboveLimit() throws IOException {
        properties.setMaxFiles(1);
        Files.writeString(sourceDirectory.resolve("one.txt"), "1", StandardCharsets.UTF_8);
        Files.writeString(sourceDirectory.resolve("two.txt"), "2", StandardCharsets.UTF_8);

        assertCopyFailure(
                ArtifactCopyException.Reason.LIMIT_EXCEEDED,
                () -> newCopier(false).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT)
        );
        assertFalse(Files.exists(targetDirectory));
    }

    @Test
    void shouldRejectDirectoryCountAboveLimit() throws IOException {
        properties.setMaxDirectories(1);
        Files.createDirectory(sourceDirectory.resolve("one"));
        Files.createDirectory(sourceDirectory.resolve("two"));

        assertCopyFailure(
                ArtifactCopyException.Reason.LIMIT_EXCEEDED,
                () -> newCopier(false).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT)
        );
    }

    @Test
    void shouldRejectDirectoryDepthAboveLimit() throws IOException {
        properties.setMaxDirectoryDepth(1);
        Files.createDirectories(sourceDirectory.resolve("one/two"));

        assertCopyFailure(
                ArtifactCopyException.Reason.LIMIT_EXCEEDED,
                () -> newCopier(false).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT)
        );
    }

    @Test
    void shouldRejectFileSizeAboveLimit() throws IOException {
        properties.setMaxFileBytes(1_024);
        Files.write(sourceDirectory.resolve("large.bin"), new byte[1_025]);

        assertCopyFailure(
                ArtifactCopyException.Reason.LIMIT_EXCEEDED,
                () -> newCopier(false).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT)
        );
    }

    @Test
    void shouldRejectTotalBytesAboveLimit() throws IOException {
        properties.setMaxFileBytes(700_000);
        properties.setMaxTotalBytes(1_048_576);
        Files.write(sourceDirectory.resolve("one.bin"), new byte[600_000]);
        Files.write(sourceDirectory.resolve("two.bin"), new byte[600_000]);

        assertCopyFailure(
                ArtifactCopyException.Reason.LIMIT_EXCEEDED,
                () -> newCopier(false).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT)
        );
    }

    @Test
    void shouldRejectSymbolicLinksEvenWhenTheirNamesAreExcluded() throws IOException {
        Files.writeString(sourceDirectory.resolve("index.html"), "source", StandardCharsets.UTF_8);
        Path link = sourceDirectory.resolve(".ai-code-install.stamp");
        try {
            Files.createSymbolicLink(link, Path.of("index.html"));
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "symbolic links are unavailable in this environment");
        }

        assertCopyFailure(
                ArtifactCopyException.Reason.UNSAFE_SYMBOLIC_LINK,
                () -> newCopier(false).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.GENERATED_SOURCE)
        );
        assertFalse(Files.exists(targetDirectory));
    }

    @Test
    void shouldDeleteStagedTargetWhenRobocopyResultIsIncomplete() throws Exception {
        Files.writeString(sourceDirectory.resolve("index.html"), "source", StandardCharsets.UTF_8);

        assertCopyFailure(
                ArtifactCopyException.Reason.INCOMPLETE_COPY,
                () -> newCopier(true).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT)
        );
        assertFalse(Files.exists(targetDirectory));
    }

    @Test
    void shouldRejectAndCleanCopyWhenSourceChangesDuringRobocopy() throws Exception {
        Files.writeString(sourceDirectory.resolve("index.html"), "source", StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            Path target = invocation.getArgument(1);
            Files.copy(source.resolve("index.html"), target.resolve("index.html"));
            Files.writeString(source.resolve("index.html"), "changed source", StandardCharsets.UTF_8);
            return null;
        }).when(robocopyDirectoryCopier).copy(
                any(Path.class),
                any(Path.class),
                anyList(),
                anyList()
        );

        assertCopyFailure(
                ArtifactCopyException.Reason.SOURCE_CHANGED,
                () -> newCopier(true).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT)
        );
        assertFalse(Files.exists(targetDirectory));
    }

    @Test
    void shouldCopyCompleteRobocopyResultAfterValidation() throws Exception {
        Files.writeString(sourceDirectory.resolve("index.html"), "source", StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            Path source = invocation.getArgument(0);
            Path target = invocation.getArgument(1);
            Files.copy(source.resolve("index.html"), target.resolve("index.html"));
            return null;
        }).when(robocopyDirectoryCopier).copy(
                any(Path.class),
                any(Path.class),
                anyList(),
                anyList()
        );

        newCopier(true).copy(sourceDirectory, targetDirectory, ArtifactCopyProfile.DEPLOYMENT);

        assertEquals("source", Files.readString(targetDirectory.resolve("index.html"), StandardCharsets.UTF_8));
    }

    private ArtifactDirectoryCopier newCopier(boolean windows) {
        return new ArtifactDirectoryCopier(properties, robocopyDirectoryCopier, windows);
    }

    private void assertCopyFailure(ArtifactCopyException.Reason expectedReason, Executable executable) {
        ArtifactCopyException exception = assertThrows(ArtifactCopyException.class, executable);
        assertEquals(expectedReason, exception.reason());
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDirectory);
    }

    private Path createTestDirectory(String prefix) throws IOException {
        Path testRoot = Path.of("target", "test-temp").toAbsolutePath().normalize();
        Files.createDirectories(testRoot);
        return Files.createDirectories(testRoot.resolve(prefix + "-" + UUID.randomUUID()));
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
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