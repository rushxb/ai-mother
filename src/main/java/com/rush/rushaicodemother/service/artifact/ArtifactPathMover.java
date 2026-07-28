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
 * 移动工件目录而不进行替换，并容忍有限的瞬时访问拒绝。
 *
 * <p>Windows 病毒扫描程序和文件索引器可以短暂保持新复制的目录打开。一个
 * 简短的、可配置的重试缩小了特定于平台的可靠性差距，而不会隐藏永久性的
 * 权限失败或覆盖另一个请求发布的目的地。</p>
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

    /** 将 {@code source} 移动到不存在的 {@code target}；现有目标永远不会被替换。 */
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

    /** 移动{@code Once}。 */
    private void moveOnce(Path source, Path target) throws IOException {
        try {
            moveOperation.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            moveOperation.move(source, target);
        }
    }

    /** 等待重试完成。 */
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
