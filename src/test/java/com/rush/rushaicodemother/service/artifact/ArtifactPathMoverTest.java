package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPathMoverTest {

    private Path testDirectory;
    private Path source;
    private Path target;

    @BeforeEach
    void setUp() throws IOException {
        testDirectory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "artifact-move-");
        source = Files.createDirectory(testDirectory.resolve("source"));
        Files.writeString(source.resolve("artifact.txt"), "artifact", StandardCharsets.UTF_8);
        target = testDirectory.resolve("target");
    }

    @Test
    void shouldRetryTransientAccessDenialAndPublishDirectory() throws IOException {
        ArtifactLifecycleProperties properties = retryProperties(3, 0);
        AtomicInteger attempts = new AtomicInteger();
        ArtifactPathMover mover = new ArtifactPathMover(properties, (moveSource, moveTarget, options) -> {
            if (attempts.incrementAndGet() < 3) {
                throw accessDenied(moveSource, moveTarget);
            }
            return Files.move(moveSource, moveTarget, options);
        });

        mover.move(source, target);

        assertEquals(3, attempts.get());
        assertFalse(Files.exists(source));
        assertEquals("artifact", Files.readString(target.resolve("artifact.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldStopRetryingWhenAnotherPublisherCreatesTarget() throws IOException {
        ArtifactLifecycleProperties properties = retryProperties(5, 0);
        AtomicInteger attempts = new AtomicInteger();
        ArtifactPathMover mover = new ArtifactPathMover(properties, (moveSource, moveTarget, options) -> {
            attempts.incrementAndGet();
            Files.createDirectory(moveTarget);
            throw accessDenied(moveSource, moveTarget);
        });

        assertThrows(AccessDeniedException.class, () -> mover.move(source, target));

        assertEquals(1, attempts.get());
        assertTrue(Files.isDirectory(source));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void shouldExposePermanentAccessDenialAfterConfiguredAttempts() {
        ArtifactLifecycleProperties properties = retryProperties(2, 0);
        AtomicInteger attempts = new AtomicInteger();
        ArtifactPathMover mover = new ArtifactPathMover(properties, (moveSource, moveTarget, options) -> {
            attempts.incrementAndGet();
            throw accessDenied(moveSource, moveTarget);
        });

        assertThrows(AccessDeniedException.class, () -> mover.move(source, target));

        assertEquals(2, attempts.get());
        assertTrue(Files.isDirectory(source));
        assertFalse(Files.exists(target));
    }

    @Test
    void shouldFallBackWhenAtomicMoveIsUnsupported() throws IOException {
        ArtifactLifecycleProperties properties = retryProperties(1, 0);
        AtomicInteger attempts = new AtomicInteger();
        ArtifactPathMover mover = new ArtifactPathMover(properties, (moveSource, moveTarget, options) -> {
            attempts.incrementAndGet();
            if (containsAtomicMove(options)) {
                throw new AtomicMoveNotSupportedException(
                        moveSource.toString(),
                        moveTarget.toString(),
                        "atomic move unavailable"
                );
            }
            return Files.move(moveSource, moveTarget, options);
        });

        mover.move(source, target);

        assertEquals(2, attempts.get());
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void shouldNotRetryUnrelatedIoFailure() {
        ArtifactLifecycleProperties properties = retryProperties(5, 0);
        AtomicInteger attempts = new AtomicInteger();
        ArtifactPathMover mover = new ArtifactPathMover(properties, (moveSource, moveTarget, options) -> {
            attempts.incrementAndGet();
            throw new IOException("permanent I/O failure");
        });

        IOException exception = assertThrows(IOException.class, () -> mover.move(source, target));

        assertEquals("permanent I/O failure", exception.getMessage());
        assertEquals(1, attempts.get());
    }

    @Test
    void shouldPreserveInterruptStatusWhileWaitingForRetry() {
        ArtifactLifecycleProperties properties = retryProperties(2, 50);
        ArtifactPathMover mover = new ArtifactPathMover(properties, (moveSource, moveTarget, options) -> {
            throw accessDenied(moveSource, moveTarget);
        });

        Thread.currentThread().interrupt();
        try {
            InterruptedIOException exception = assertThrows(
                    InterruptedIOException.class,
                    () -> mover.move(source, target)
            );

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(exception.getCause() instanceof AccessDeniedException);
        } finally {
            Thread.interrupted();
        }
    }

    private ArtifactLifecycleProperties retryProperties(int maxAttempts, long retryDelayMillis) {
        ArtifactLifecycleProperties properties = new ArtifactLifecycleProperties();
        properties.setPublishMaxAttempts(maxAttempts);
        properties.setPublishRetryDelayMillis(retryDelayMillis);
        return properties;
    }

    private AccessDeniedException accessDenied(Path moveSource, Path moveTarget) {
        return new AccessDeniedException(moveSource.toString(), moveTarget.toString(), "temporarily locked");
    }

    private boolean containsAtomicMove(CopyOption[] options) {
        return Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (testDirectory == null || !Files.exists(testDirectory)) {
            return;
        }
        try (var paths = Files.walk(testDirectory)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to clean artifact mover test directory", exception);
                        }
                    });
        }
    }
}
