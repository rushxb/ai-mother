package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Moves artifact directories without replacement and tolerates bounded transient access denials.
 *
 * <p>Windows virus scanners and file indexers can briefly keep a newly copied directory open. A
 * short, configurable retry closes that platform-specific reliability gap without hiding permanent
 * permission failures or overwriting a destination published by another request.</p>
 */
@Component
public class ArtifactPathMover {

    private final ArtifactLifecycleProperties properties;
    private final MoveOperation moveOperation;

    @Autowired
    public ArtifactPathMover(ArtifactLifecycleProperties properties) {
        this(properties, Files::move);
    }

    ArtifactPathMover(ArtifactLifecycleProperties properties, MoveOperation moveOperation) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.moveOperation = Objects.requireNonNull(moveOperation, "moveOperation must not be null");
    }

    /** Moves {@code source} to an absent {@code target}; an existing target is never replaced. */
    public void move(Path source, Path target) throws IOException {
        Path normalizedSource = requirePath(source, "source");
        Path normalizedTarget = requirePath(target, "target");
        int maxAttempts = properties.getPublishMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                moveOnce(normalizedSource, normalizedTarget);
                return;
            } catch (AccessDeniedException exception) {
                if (attempt >= maxAttempts
                        || Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                    throw exception;
                }
                awaitRetry(exception);
            }
        }
    }

    private void moveOnce(Path source, Path target) throws IOException {
        try {
            moveOperation.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            moveOperation.move(source, target);
        }
    }

    private void awaitRetry(AccessDeniedException accessDeniedException) throws IOException {
        try {
            Thread.sleep(properties.getPublishRetryDelayMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(
                    "Interrupted while waiting to retry artifact directory publication"
            );
            interrupted.initCause(accessDeniedException);
            throw interrupted;
        }
    }

    private Path requirePath(Path path, String label) {
        return Objects.requireNonNull(path, label + " must not be null")
                .toAbsolutePath()
                .normalize();
    }

    @FunctionalInterface
    interface MoveOperation {
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
