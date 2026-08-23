package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.service.artifact.ArtifactPathMover;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 通过原子版本指针发布一个任务/纪元工作区。
 *
 * <p> 完成的目录首先被移动到一个唯一的、版本化的位置。仅在租约结束后
 * 更新后，如果小指针文件变得可见，则重新验证确切的耐用栅栏。
 * 这避免了 Windows 上的非原子目录替换并保持以前的版本可用
 * 用于回滚和诊断。</p>
 */
@Service
public class GenerationWorkspacePublicationService {

    private static final long MAX_MANAGED_LOCK_POLL_NANOS = Duration.ofMillis(100).toNanos();

    private final GenerationWorkspacePublicationCatalog publicationCatalog;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final GenerationTaskFenceGuard fenceGuard;
    private final ArtifactPathMover pathMover;
    private final ArtifactLifecycleProperties properties;
    private final GenerationWorkspacePublicationJournalRepository journalRepository;
    private final Clock clock;

    @Autowired
    public GenerationWorkspacePublicationService(
            GenerationWorkspacePublicationCatalog publicationCatalog,
            GenerationTaskRuntimeLifecycleService runtimeLifecycleService,
            GenerationTaskFenceGuard fenceGuard,
            ArtifactPathMover pathMover,
            ArtifactLifecycleProperties properties,
            GenerationWorkspacePublicationJournalRepository journalRepository
    ) {
        this(publicationCatalog, runtimeLifecycleService, fenceGuard, pathMover,
                properties, journalRepository, Clock.systemUTC());
    }

    GenerationWorkspacePublicationService(
            GenerationWorkspacePublicationCatalog publicationCatalog,
            GenerationTaskRuntimeLifecycleService runtimeLifecycleService,
            GenerationTaskFenceGuard fenceGuard,
            ArtifactPathMover pathMover,
            ArtifactLifecycleProperties properties,
            GenerationWorkspacePublicationJournalRepository journalRepository,
            Clock clock
    ) {
        this.publicationCatalog = Objects.requireNonNull(publicationCatalog, "publicationCatalog");
        this.runtimeLifecycleService = Objects.requireNonNull(runtimeLifecycleService, "runtimeLifecycleService");
        this.fenceGuard = Objects.requireNonNull(fenceGuard, "fenceGuard");
        this.pathMover = Objects.requireNonNull(pathMover, "pathMover");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 发布并提交应用程序元数据，同时仍可以进行文件系统回滚。 */
    public GenerationWorkspacePublicationResult publishWithMetadata(
            GenerationSession session,
            GenerationWorkspacePublicationCommitter metadataCommit) {
        if (session == null || session.executionContext() == null
                || session.executionContext().executionFence() == null
                || session.executionWorkspace() == null) {
            throw new GenerationExecutionPolicyException(
                    "managed generation session has no publishable execution workspace");
        }
        GenerationExecutionContext executionContext = session.executionContext();
        return publishWithMetadata(
                executionContext.executionFence(),
                session.executionWorkspace(),
                metadataCommit,
                executionContext);
    }

    public GenerationWorkspacePublicationResult publishWithMetadata(
            GenerationExecutionFence fence,
            GenerationExecutionWorkspace executionWorkspace,
            GenerationWorkspacePublicationCommitter metadataCommit
    ) {
        return publishWithMetadata(fence, executionWorkspace, metadataCommit, null);
    }

    /** 发布并元数据。 */
    private GenerationWorkspacePublicationResult publishWithMetadata(
            GenerationExecutionFence fence,
            GenerationExecutionWorkspace executionWorkspace,
            GenerationWorkspacePublicationCommitter metadataCommit,
            GenerationExecutionContext executionContext
    ) {
        requireIdentity(fence, executionWorkspace);
        Objects.requireNonNull(metadataCommit, "metadataCommit");
        if (executionContext != null) {
            executionContext.assertCanContinue();
        }
        GenerationWorkspacePublicationPointer candidate = GenerationWorkspacePublicationPointer.from(
                executionWorkspace.appId(), executionWorkspace.codeGenType(), fence, clock.instant());
        try (PublicationLock ignored = acquireLock(executionWorkspace.appId(), executionContext)) {
            if (executionContext != null) {
                executionContext.assertCanContinue();
            }
            return publishLocked(fence, executionWorkspace, candidate, metadataCommit);
        } catch (BusinessException | GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Execution workspace publication failed", exception);
        }
    }

    /** 仅协调其指针在崩溃之前已成为用户可见的发布。 */
    public ReconciliationOutcome reconcile(
            GenerationWorkspacePublicationJournalEntry entry,
            GenerationWorkspacePublicationCommitter metadataCommit
    ) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(metadataCommit, "metadataCommit");
        GenerationWorkspacePublicationPointer pointer = entry.pointer();
        try (PublicationLock ignored = acquireLock(pointer.appId())) {
            Optional<GenerationWorkspacePublicationPointer> current = publicationCatalog.findCurrent(
                    pointer.appId(), pointer.codeGenType());
            if (current.filter(pointer::equals).isPresent()) {
                publicationCatalog.resolveWorkspace(pointer);
                if (entry.status() != GenerationWorkspacePublicationJournalStatus.COMMITTED) {
                    metadataCommit.commit(pointer);
                }
                return ReconciliationOutcome.COMMITTED;
            }
            GenerationWorkspacePublicationPointer active = current.orElse(null);
            if (active != null
                    && active.taskId().equals(pointer.taskId())
                    && active.executionEpoch() == pointer.executionEpoch()) {
                throw new IllegalStateException(
                        "active publication timestamp conflicts with the durable journal");
            }
            if (active != null && active.executionEpoch() >= pointer.executionEpoch()) {
                journalRepository.markSuperseded(
                        pointer, "a newer publication pointer is already active", clock.instant());
                return ReconciliationOutcome.SUPERSEDED;
            }
            if (entry.status() == GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED
                    || entry.status() == GenerationWorkspacePublicationJournalStatus.ROLLBACK_REQUIRED) {
                journalRepository.markRolledBack(
                        pointer, "publication pointer is no longer active", clock.instant());
                return ReconciliationOutcome.ROLLED_BACK;
            }
            return ReconciliationOutcome.PENDING_TASK_RETRY;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Publication reconciliation failed", exception);
        }
    }

    /** 发布{@code Locked}。 */
    private GenerationWorkspacePublicationResult publishLocked(
            GenerationExecutionFence fence,
            GenerationExecutionWorkspace executionWorkspace,
            GenerationWorkspacePublicationPointer candidate,
            GenerationWorkspacePublicationCommitter metadataCommit
    ) throws IOException {
        Optional<GenerationWorkspacePublicationPointer> current = publicationCatalog.findCurrent(
                candidate.appId(), candidate.codeGenType());
        GenerationWorkspacePublicationPointer journalCandidate = current
                .filter(existing -> existing.ownedBy(fence))
                .orElse(candidate);
        GenerationWorkspacePublicationJournalEntry journal = journalRepository.prepare(
                journalCandidate, clock.instant());
        GenerationWorkspacePublicationPointer pointer = journal.pointer();
        Optional<GenerationWorkspacePublicationPointer> ownedCurrent =
                current.filter(existing -> existing.ownedBy(fence));
        if (ownedCurrent.isPresent()) {
            if (!ownedCurrent.get().equals(pointer)) {
                throw new IllegalStateException(
                        "active publication pointer conflicts with the durable journal");
            }
            Path destination = publicationCatalog.versionWorkspacePath(pointer);
            publicationCatalog.validatePublishedVersion(destination, pointer);
            if (journal.status() == GenerationWorkspacePublicationJournalStatus.COMMITTED) {
                return new GenerationWorkspacePublicationResult(
                        GenerationWorkspacePublicationResult.Status.ALREADY_PUBLISHED,
                        current.get(),
                        destination.toRealPath(LinkOption.NOFOLLOW_LINKS),
                        clock.instant()
                );
            }
            Path publishedWorkspace = destination.toRealPath(LinkOption.NOFOLLOW_LINKS);
            runtimeLifecycleService.renewForCriticalSection(fence);
            fenceGuard.assertCurrent(fence);
            metadataCommit.commit(pointer);
            return new GenerationWorkspacePublicationResult(
                    GenerationWorkspacePublicationResult.Status.ALREADY_PUBLISHED,
                    current.get(),
                    publishedWorkspace,
                    clock.instant()
            );
        }

        if (journal.status() == GenerationWorkspacePublicationJournalStatus.COMMITTED) {
            throw new IllegalStateException(
                    "committed publication journal is not the active application pointer");
        }

        Path source = executionWorkspace.workspace().canonicalRootPath();
        Path destination = null;
        GenerationWorkspacePublicationCatalog.PointerSnapshot previousPointer = null;
        boolean restoreWorkspaceOnRollback = false;
        boolean pointerActivated = false;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            validateExecutionSource(executionWorkspace, source);
            Path versionParent = publicationCatalog.prepareVersionParent(pointer);
            destination = versionParent.resolve("workspace").normalize();
            if (destination.getParent() == null || !destination.getParent().equals(versionParent)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "published workspace destination escaped its version directory");
            }

            boolean sourceExists = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
            boolean destinationExists = Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS);
            if (sourceExists && destinationExists) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "both execution and published workspace versions exist");
            }
            if (!sourceExists && !destinationExists) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "execution workspace disappeared before publication");
            }
            if (sourceExists) {
                publicationCatalog.writeOwnerMarker(source, pointer);
            } else {
                publicationCatalog.validatePublishedVersion(destination, pointer);
                restoreWorkspaceOnRollback = true;
            }

            runtimeLifecycleService.renewForCriticalSection(fence);
            fenceGuard.assertCurrent(fence);
            previousPointer = publicationCatalog.snapshot(pointer.appId(), pointer.codeGenType());
            if (sourceExists) {
                pathMover.move(source, destination);
                restoreWorkspaceOnRollback = true;
            }
            publicationCatalog.validatePublishedVersion(destination, pointer);
            runtimeLifecycleService.renewForCriticalSection(fence);
            fenceGuard.assertCurrent(fence);

            publicationCatalog.activate(pointer);
            pointerActivated = true;
            journalRepository.markFilesystemActivated(pointer, clock.instant());
            Path publishedWorkspace = destination.toRealPath(LinkOption.NOFOLLOW_LINKS);

            runtimeLifecycleService.renewForCriticalSection(fence);
            fenceGuard.assertCurrent(fence);
            metadataCommit.commit(pointer);
            return new GenerationWorkspacePublicationResult(
                    GenerationWorkspacePublicationResult.Status.PUBLISHED,
                    pointer,
                    publishedWorkspace,
                    clock.instant()
            );
        } catch (Throwable publicationFailure) {
            int suppressedBeforeRollback = publicationFailure.getSuppressed().length;
            rollback(pointer, previousPointer, source, destination, restoreWorkspaceOnRollback,
                    pointerActivated,
                    publicationFailure);
            boolean rollbackComplete = publicationFailure.getSuppressed().length == suppressedBeforeRollback;
            recordRollbackState(pointer, publicationFailure, rollbackComplete);
            if (publicationFailure instanceof Error errorFailure) {
                throw errorFailure;
            }
            throw new GenerationWorkspacePublicationException(
                    rollbackComplete, publicationFailure);
        }
    }

    /** 记录回滚状态相关指标或状态。 */
    private void recordRollbackState(GenerationWorkspacePublicationPointer pointer,
                                     Throwable publicationFailure,
                                     boolean rollbackComplete) {
        String diagnostic = LogExceptionSanitizer.sanitizeMessage(publicationFailure);
        try {
            if (rollbackComplete) {
                journalRepository.markRolledBack(pointer, diagnostic, clock.instant());
            } else {
                journalRepository.markRollbackRequired(pointer, diagnostic, clock.instant());
            }
        } catch (Throwable journalFailure) {
            publicationFailure.addSuppressed(journalFailure);
        }
    }

    /** 处理回滚。 */
    private void rollback(GenerationWorkspacePublicationPointer pointer,
                           GenerationWorkspacePublicationCatalog.PointerSnapshot previousPointer,
                           Path source,
                           Path destination,
                           boolean restoreWorkspace,
                           boolean pointerActivated,
                           Throwable publicationFailure) {
        boolean pointerRestored = !pointerActivated;
        if (pointerActivated) {
            if (previousPointer == null) {
                publicationFailure.addSuppressed(new IllegalStateException(
                        "publication pointer snapshot is unavailable for rollback"));
            } else {
                try {
                    publicationCatalog.restore(pointer.appId(), pointer.codeGenType(), previousPointer);
                    pointerRestored = true;
                } catch (Throwable pointerRollbackFailure) {
                    publicationFailure.addSuppressed(pointerRollbackFailure);
                }
            }
        }
        // 如果指针恢复失败，请保留确切的已发布目录。  活跃的
        // 指针绝不能指向补偿移走的目录。
        if (restoreWorkspace && pointerRestored && destination != null
                && Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            try {
                pathMover.move(destination, source);
            } catch (Throwable moveRollbackFailure) {
                publicationFailure.addSuppressed(moveRollbackFailure);
            }
        }
    }

    private PublicationLock acquireLock(Long appId) throws IOException {
        return acquireLock(appId, null);
    }

    /** 获取锁。 */
    private PublicationLock acquireLock(Long appId,
                                        GenerationExecutionContext executionContext) throws IOException {
        Duration timeout = properties.getPublicationLockTimeout();
        boolean boundedByTaskDeadline = false;
        if (executionContext != null) {
            executionContext.assertCanContinue();
            Duration remaining = executionContext.remainingDuration();
            if (remaining.isZero()) {
                throw new GenerationDeadlineExceededException(executionContext.taskId());
            }
            if (remaining.compareTo(timeout) < 0) {
                timeout = remaining;
                boundedByTaskDeadline = true;
            }
        }
        Path lockPath = publicationCatalog.lockPath(appId);
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(lockPath)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "publication lock cannot be a symbolic link");
        }
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        );
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "publication lock is unsafe");
            }
            long timeoutNanos = toNanosSaturated(timeout);
            long retryDelayNanos = toNanosSaturated(
                    Duration.ofMillis(Math.max(10L, properties.getPublishRetryDelayMillis())));
            if (executionContext != null) {
                retryDelayNanos = Math.min(retryDelayNanos, MAX_MANAGED_LOCK_POLL_NANOS);
            }
            long startedNanos = System.nanoTime();
            while (true) {
                if (executionContext != null) {
                    executionContext.assertCanContinue();
                }
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        if (executionContext != null) {
                            executionContext.assertCanContinue();
                        }
                        return new PublicationLock(channel, lock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // 该 JVM 中的另一个工作线程拥有相同的应用程序发布锁。
                }
                long elapsedNanos = System.nanoTime() - startedNanos;
                if (elapsedNanos >= timeoutNanos) {
                    if (executionContext != null) {
                        executionContext.assertCanContinue();
                        if (boundedByTaskDeadline) {
                            throw new GenerationDeadlineExceededException(executionContext.taskId());
                        }
                    }
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "Timed out waiting for the application publication lock");
                }
                long sleepNanos = Math.min(retryDelayNanos, timeoutNanos - elapsedNanos);
                if (executionContext != null) {
                    long taskRemainingNanos = toNanosSaturated(executionContext.remainingDuration());
                    if (taskRemainingNanos <= 0L) {
                        throw new GenerationDeadlineExceededException(executionContext.taskId());
                    }
                    sleepNanos = Math.min(sleepNanos, taskRemainingNanos);
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.max(1L, sleepNanos));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure = new InterruptedIOException(
                            "Interrupted while waiting for the application publication lock");
                    failure.initCause(interrupted);
                    throw failure;
                }
            }
        } catch (Throwable failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** 将目标时长转换为防溢出的纳秒数。 */
    private long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** 校验{@code ate}执行来源是否有效。 */
    private void validateExecutionSource(GenerationExecutionWorkspace executionWorkspace,
                                         Path source) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (!normalized.equals(executionWorkspace.workspace().canonicalRootPath())
                || !normalized.startsWith(executionWorkspace.typeRootPath())
                || normalized.getFileName() == null
                || !"workspace".equals(normalized.getFileName().toString())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "execution workspace publication source is invalid");
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "execution workspace publication source is unsafe");
            }
        }
    }

    private void requireIdentity(GenerationExecutionFence fence,
                                 GenerationExecutionWorkspace executionWorkspace) {
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(executionWorkspace, "executionWorkspace");
        if (!fence.equals(executionWorkspace.fence())) {
            throw new GenerationExecutionPolicyException(
                    "execution workspace is owned by a different durable fence");
        }
    }

    private record PublicationLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        private PublicationLock {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(lock, "lock");
        }

        /** 关闭发布锁并释放资源。 */
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public enum ReconciliationOutcome {
        COMMITTED,
        SUPERSEDED,
        ROLLED_BACK,
        PENDING_TASK_RETRY
    }
}
