package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceDirectoryFingerprint;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceDirectoryMetadata;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 应用级生成快照的唯一文件系统边界。
 *
 * <p>快照名称只承担展示和索引职责，磁盘容器固定使用不可变 {@code snapshotId}。
 * 所有消费方必须先通过本服务验证 manifest、逻辑 scope 与 payload 指纹，不能自行拼接路径。</p>
 */
@Service
@Slf4j
public class GenerationSnapshotWorkspaceService {

    private static final int SNAPSHOT_LOCK_STRIPES = 64;
    private static final String STAGING_PREFIX = ".snapshot-staging-";

    private final CodeStorageProperties storageProperties;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;
    private final SnapshotManifestCodec manifestCodec;
    private final Clock clock;
    private final ReentrantLock[] snapshotLocks;

    @Autowired
    public GenerationSnapshotWorkspaceService(
            CodeStorageProperties storageProperties,
            WorkspaceFileSystemService workspaceFileSystemService,
            SnapshotNamePolicy snapshotNamePolicy
    ) {
        this(storageProperties, workspaceFileSystemService, snapshotNamePolicy, Clock.systemUTC());
    }

    GenerationSnapshotWorkspaceService(
            CodeStorageProperties storageProperties,
            WorkspaceFileSystemService workspaceFileSystemService,
            SnapshotNamePolicy snapshotNamePolicy,
            Clock clock
    ) {
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
        this.snapshotNamePolicy = Objects.requireNonNull(snapshotNamePolicy, "snapshotNamePolicy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.manifestCodec = new SnapshotManifestCodec();
        this.snapshotLocks = createSnapshotLocks();
    }

    /** 创建一个新的不可变快照；同名快照已存在时拒绝覆盖。 */
    public StoredSnapshot capture(SnapshotCapture capture, Runnable continuationCheck) throws IOException {
        return capture(capture, continuationCheck, false);
    }

    /**
     * 创建快照；仅在名称、scope、用途、任务和执行纪元完全相同时复用已有快照。
     *
     * <p>该语义用于同一执行的幂等重入，不能把同名快照当作可覆盖槽位。</p>
     */
    public StoredSnapshot captureOrReuse(SnapshotCapture capture, Runnable continuationCheck) throws IOException {
        return capture(capture, continuationCheck, true);
    }

    /** 解析并严格校验 selector 对应的快照。 */
    public StoredSnapshot requireSnapshot(SnapshotSelector selector) throws IOException {
        Objects.requireNonNull(selector, "selector must not be null");
        String snapshotName = snapshotNamePolicy.validateRequired(selector.snapshotName());
        SnapshotSelector canonicalSelector = new SnapshotSelector(
                snapshotName,
                selector.scope(),
                selector.expectedSnapshotId(),
                selector.expectedKind(),
                selector.expectedCreatorTaskId(),
                selector.expectedCreatorExecutionEpoch(),
                selector.expectedManifestSha256()
        );
        if (!canonicalSelector.expectedSnapshotId().isBlank()) {
            Path containerPath = resolveContainerById(
                    canonicalSelector.scope().appId(),
                    canonicalSelector.expectedSnapshotId()
            );
            return requireSnapshotLocked(canonicalSelector, containerPath);
        }

        List<StoredSnapshot> matches = listSnapshots(canonicalSelector.scope().appId()).stream()
                .filter(snapshot -> snapshot.snapshotName().equals(canonicalSelector.snapshotName()))
                .filter(snapshot -> selectorMatches(canonicalSelector, snapshot))
                .toList();
        if (matches.isEmpty()) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.NOT_FOUND,
                    "snapshot does not exist for the requested workspace"
            );
        }
        if (matches.size() > 1) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.AMBIGUOUS_NAME,
                    "snapshot name resolves to multiple immutable identities"
            );
        }
        return matches.get(0);
    }

    /** 列出应用下所有已提交且内容完整的快照。 */
    public List<StoredSnapshot> listSnapshots(Long appId) throws IOException {
        Path applicationRoot = resolveApplicationRoot(appId);
        if (!workspaceFileSystemService.isDirectory(applicationRoot)) {
            return List.of();
        }
        List<StoredSnapshot> snapshots = new ArrayList<>();
        for (WorkspaceDirectoryMetadata directory : workspaceFileSystemService.listChildDirectories(applicationRoot)) {
            String directoryName = directory.name();
            if (directoryName.startsWith(STAGING_PREFIX)) {
                continue;
            }
            String snapshotId = requireCanonicalSnapshotId(directoryName);
            Path containerPath = resolveContainerById(appId, snapshotId);
            snapshots.add(requireSnapshotLocked(null, containerPath));
        }
        snapshots.sort(Comparator.comparing(StoredSnapshot::createdAt).reversed()
                .thenComparing(StoredSnapshot::snapshotId));
        return List.copyOf(snapshots);
    }

    /**
     * 在同一不可变身份下重新验证快照并恢复工作区。
     *
     * <p>文件系统服务会在目标位移前再次核对 staging 指纹，因此 payload 在读取后被修改也会失败关闭。</p>
     */
    public WorkspaceCopyResult restore(SnapshotSelector selector,
                                       Path targetDirectory,
                                       Runnable continuationCheck) throws IOException {
        Objects.requireNonNull(targetDirectory, "targetDirectory must not be null");
        StoredSnapshot resolved = requireSnapshot(selector);
        SnapshotSelector exactSelector = SnapshotSelector.exact(resolved);
        ReentrantLock lock = snapshotLockFor(resolved.containerPath());
        lock.lock();
        try {
            StoredSnapshot snapshot = requireSnapshotUnlocked(exactSelector, resolved.containerPath());
            Runnable effectiveCheck = effectiveContinuationCheck(continuationCheck);
            effectiveCheck.run();
            Path target = targetDirectory.toAbsolutePath().normalize();
            return workspaceFileSystemService.isDirectory(target)
                    ? workspaceFileSystemService.replaceDirectory(
                            snapshot.payloadPath(), target, snapshot.fingerprint(), effectiveCheck)
                    : workspaceFileSystemService.copyDirectory(
                            snapshot.payloadPath(), target, snapshot.fingerprint(), effectiveCheck);
        } finally {
            lock.unlock();
        }
    }

    /** 删除 selector 精确解析到的不可变快照容器。 */
    public void deleteSnapshot(SnapshotSelector selector) throws IOException {
        deleteSnapshot(selector, null);
    }

    /** 在调用方执行权检查后删除 selector 精确解析到的不可变快照容器。 */
    public void deleteSnapshot(SnapshotSelector selector,
                               Runnable continuationCheck) throws IOException {
        StoredSnapshot resolved = requireSnapshot(selector);
        SnapshotSelector exactSelector = SnapshotSelector.exact(resolved);
        ReentrantLock lock = snapshotLockFor(resolved.containerPath());
        lock.lock();
        try {
            StoredSnapshot snapshot = requireSnapshotUnlocked(exactSelector, resolved.containerPath());
            effectiveContinuationCheck(continuationCheck).run();
            workspaceFileSystemService.deleteDirectory(snapshot.containerPath());
        } finally {
            lock.unlock();
        }
    }

    /** 解析应用快照根，不因读取操作创建目录。 */
    public Path resolveApplicationRoot(Long appId) {
        requireAppId(appId);
        Path storageRoot = storageProperties.snapshotRoot();
        Path applicationRoot = storageRoot.resolve(String.valueOf(appId)).normalize();
        ensureDirectChild(
                storageRoot,
                applicationRoot,
                "Application snapshot root must be a direct child of the snapshot storage root"
        );
        validateExistingDirectory(storageRoot, "Snapshot storage root is unsafe");
        validateExistingDirectory(applicationRoot, "Application snapshot root is unsafe");
        return applicationRoot;
    }

    /** 创建并验证应用快照根。 */
    public Path prepareApplicationRoot(Long appId) {
        Path applicationRoot = resolveApplicationRoot(appId);
        try {
            workspaceFileSystemService.ensureDirectory(storageProperties.snapshotRoot());
            return workspaceFileSystemService.ensureDirectory(applicationRoot);
        } catch (IOException exception) {
            throw storageFailure("Failed to prepare the application snapshot root", exception);
        }
    }

    /** 仅按规范 UUID 解析物理容器，名称不得参与路径构造。 */
    Path resolveContainerById(Long appId, String snapshotId) throws SnapshotStoreException {
        String canonicalId = requireCanonicalSnapshotId(snapshotId);
        Path applicationRoot = resolveApplicationRoot(appId);
        Path containerPath = applicationRoot.resolve(canonicalId).normalize();
        ensureDirectChild(
                applicationRoot,
                containerPath,
                "Snapshot container must be a direct child of the application snapshot root"
        );
        validateExistingDirectory(containerPath, "Snapshot container is unsafe");
        return containerPath;
    }

    private StoredSnapshot capture(SnapshotCapture capture,
                                   Runnable continuationCheck,
                                   boolean reuseMatching) throws IOException {
        Objects.requireNonNull(capture, "capture must not be null");
        String snapshotName = snapshotNamePolicy.validateRequired(capture.snapshotName());
        if (!snapshotName.equals(capture.snapshotName())) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.MANIFEST_INVALID,
                    "snapshot name is not canonical"
            );
        }
        Path applicationRoot = prepareApplicationRoot(capture.scope().appId());
        Runnable effectiveCheck = effectiveContinuationCheck(continuationCheck);
        ReentrantLock nameLock = snapshotLockFor(
                applicationRoot.resolve(".snapshot-name-lock-" + snapshotName));
        nameLock.lock();
        try {
            effectiveCheck.run();
            List<StoredSnapshot> sameNameSnapshots = listSnapshots(capture.scope().appId()).stream()
                    .filter(snapshot -> snapshot.snapshotName().equals(snapshotName))
                    .toList();
            if (!sameNameSnapshots.isEmpty()) {
                if (sameNameSnapshots.size() > 1) {
                    throw new SnapshotStoreException(
                            SnapshotStoreException.Reason.AMBIGUOUS_NAME,
                            "snapshot name resolves to multiple immutable identities"
                    );
                }
                StoredSnapshot existing = sameNameSnapshots.get(0);
                if (reuseMatching && matchesCapture(existing, capture)) {
                    effectiveCheck.run();
                    return existing;
                }
                throw new SnapshotStoreException(
                        SnapshotStoreException.Reason.ALREADY_EXISTS,
                        "snapshot name already belongs to another immutable identity"
                );
            }
            return createSnapshotUnlocked(capture, applicationRoot, effectiveCheck);
        } finally {
            nameLock.unlock();
        }
    }

    private StoredSnapshot createSnapshotUnlocked(SnapshotCapture capture,
                                                  Path applicationRoot,
                                                  Runnable continuationCheck) throws IOException {
        Runnable effectiveCheck = effectiveContinuationCheck(continuationCheck);
        String snapshotId = UUID.randomUUID().toString();
        Path finalContainer = resolveContainerById(capture.scope().appId(), snapshotId);
        Path stagingContainer = applicationRoot
                .resolve(STAGING_PREFIX + snapshotId + "-" + UUID.randomUUID())
                .normalize();
        ensureDirectChild(applicationRoot, stagingContainer, "Snapshot staging path escaped application root");

        boolean stagingPrepared = false;
        boolean finalPublished = false;
        boolean captureCommitted = false;
        Throwable operationFailure = null;
        try {
            workspaceFileSystemService.ensureDirectory(stagingContainer);
            stagingPrepared = true;
            effectiveCheck.run();
            WorkspaceCopyResult payloadCopy = workspaceFileSystemService.copyDirectory(
                    capture.sourceDirectory(),
                    payloadPath(stagingContainer),
                    effectiveCheck
            );
            SnapshotManifest manifest = SnapshotManifest.created(
                    capture,
                    payloadCopy,
                    snapshotId,
                    Instant.now(clock)
            );
            SnapshotManifestCodec.EncodedManifest encoded = manifestCodec.encode(manifest);
            effectiveCheck.run();
            workspaceFileSystemService.writeUtf8Atomically(
                    stagingContainer,
                    SnapshotManifestCodec.MANIFEST_FILE,
                    encoded.json()
            );

            // 对整个 bundle 绑定稳定指纹后再发布，最终 UUID 容器不会暴露半成品。
            WorkspaceDirectoryFingerprint bundleFingerprint = workspaceFileSystemService.fingerprintDirectory(
                    stagingContainer,
                    effectiveCheck
            );
            workspaceFileSystemService.copyDirectory(
                    stagingContainer,
                    finalContainer,
                    bundleFingerprint,
                    effectiveCheck
            );
            finalPublished = true;
            StoredSnapshot committedSnapshot = requireSnapshotUnlocked(
                    SnapshotSelector.persisted(
                            capture.snapshotName(),
                            capture.scope(),
                            snapshotId,
                            capture.kind(),
                            capture.creatorTaskId(),
                            capture.creatorExecutionEpoch(),
                            encoded.sha256()
                    ),
                    finalContainer
            );
            captureCommitted = true;
            return committedSnapshot;
        } catch (IOException | RuntimeException exception) {
            operationFailure = exception;
            throw exception;
        } finally {
            if (stagingPrepared) {
                cleanupOwnedDirectory(stagingContainer, operationFailure, captureCommitted, "staging");
            }
            if (finalPublished && operationFailure != null) {
                cleanupOwnedDirectory(finalContainer, operationFailure, false, "uncommitted container");
            }
        }
    }

    private StoredSnapshot requireSnapshotLocked(SnapshotSelector selector,
                                                 Path containerPath) throws IOException {
        ReentrantLock lock = snapshotLockFor(containerPath);
        lock.lock();
        try {
            return requireSnapshotUnlocked(selector, containerPath);
        } finally {
            lock.unlock();
        }
    }

    private StoredSnapshot requireSnapshotUnlocked(SnapshotSelector selector,
                                                   Path containerPath) throws IOException {
        if (!workspaceFileSystemService.isDirectory(containerPath)) {
            throw new SnapshotStoreException(SnapshotStoreException.Reason.NOT_FOUND, "snapshot does not exist");
        }
        Optional<String> manifestJson = workspaceFileSystemService.readOptionalUtf8(
                containerPath,
                SnapshotManifestCodec.MANIFEST_FILE,
                SnapshotManifestCodec.MAX_MANIFEST_BYTES
        );
        if (manifestJson.isEmpty()) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.MANIFEST_MISSING,
                    "snapshot manifest is missing"
            );
        }
        SnapshotManifestCodec.DecodedManifest decoded = manifestCodec.decode(manifestJson.get());
        StoredSnapshot snapshot = toStoredSnapshot(decoded.manifest(), decoded.sha256(), containerPath);
        if (selector != null) {
            requireSelectorMatches(selector, snapshot);
        }
        if (!workspaceFileSystemService.isDirectory(snapshot.payloadPath())) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.MANIFEST_INVALID,
                    "snapshot payload is missing or unsafe"
            );
        }
        WorkspaceDirectoryFingerprint actualFingerprint = workspaceFileSystemService.fingerprintDirectory(
                snapshot.payloadPath()
        );
        if (!snapshot.fingerprint().equals(actualFingerprint)) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.CONTENT_MISMATCH,
                    "snapshot payload no longer matches its manifest"
            );
        }
        return snapshot;
    }

    private StoredSnapshot toStoredSnapshot(SnapshotManifest manifest,
                                            String manifestSha256,
                                            Path containerPath) throws SnapshotStoreException {
        try {
            String normalizedName = snapshotNamePolicy.validateRequired(manifest.snapshotName());
            if (!normalizedName.equals(manifest.snapshotName())) {
                throw new IllegalArgumentException("snapshot name is not canonical");
            }
            String canonicalId = requireCanonicalSnapshotId(manifest.snapshotId());
            Path normalizedContainer = containerPath.toAbsolutePath().normalize();
            if (!canonicalId.equals(normalizedContainer.getFileName().toString())) {
                throw new IllegalArgumentException("container id does not match manifest identity");
            }
            Path expectedApplicationRoot = resolveApplicationRoot(manifest.appId());
            ensureDirectChild(
                    expectedApplicationRoot,
                    normalizedContainer,
                    "Snapshot manifest application does not own its container"
            );
            CodeGenTypeEnum workspaceType = CodeGenTypeEnum.getEnumByValue(manifest.codeGenType());
            if (workspaceType == null) {
                throw new IllegalArgumentException("unknown workspace type");
            }
            SnapshotScope scope = new SnapshotScope(manifest.appId(), workspaceType, manifest.scope());
            return new StoredSnapshot(
                    normalizedName,
                    canonicalId,
                    scope,
                    SnapshotKind.fromValue(manifest.kind()),
                    manifest.taskId(),
                    manifest.executionEpoch(),
                    normalizedContainer,
                    payloadPath(normalizedContainer),
                    new WorkspaceDirectoryFingerprint(
                            manifest.fileCount(),
                            manifest.byteCount(),
                            manifest.treeHash()
                    ),
                    manifestSha256,
                    Instant.parse(manifest.createdAt())
            );
        } catch (SnapshotStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.MANIFEST_INVALID,
                    "snapshot manifest contains invalid provenance",
                    exception
            );
        }
    }

    private void requireSelectorMatches(SnapshotSelector selector,
                                        StoredSnapshot snapshot) throws SnapshotStoreException {
        if (!selectorMatches(selector, snapshot)) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.PROVENANCE_MISMATCH,
                    "snapshot provenance does not match the requested workspace"
            );
        }
    }

    private boolean selectorMatches(SnapshotSelector selector, StoredSnapshot snapshot) {
        return selector.snapshotName().equals(snapshot.snapshotName())
                && selector.scope().equals(snapshot.scope())
                && !hasDifferentValue(selector.expectedSnapshotId(), snapshot.snapshotId())
                && (selector.expectedKind() == null || selector.expectedKind() == snapshot.kind())
                && !hasDifferentValue(selector.expectedCreatorTaskId(), snapshot.creatorTaskId())
                && (selector.expectedCreatorExecutionEpoch() == null
                || selector.expectedCreatorExecutionEpoch() == snapshot.creatorExecutionEpoch())
                && !hasDifferentValue(selector.expectedManifestSha256(), snapshot.manifestSha256());
    }

    private boolean matchesCapture(StoredSnapshot snapshot, SnapshotCapture capture) {
        return snapshot.scope().equals(capture.scope())
                && snapshot.kind() == capture.kind()
                && snapshot.creatorTaskId().equals(capture.creatorTaskId())
                && snapshot.creatorExecutionEpoch() == capture.creatorExecutionEpoch();
    }

    private boolean hasDifferentValue(String expected, String actual) {
        return expected != null && !expected.isBlank() && !expected.equals(actual);
    }

    private String requireCanonicalSnapshotId(String candidate) throws SnapshotStoreException {
        try {
            String normalized = Objects.requireNonNull(candidate, "snapshotId must not be null").trim();
            String canonical = UUID.fromString(normalized).toString();
            if (!canonical.equals(normalized)) {
                throw new IllegalArgumentException("snapshotId is not canonical");
            }
            return canonical;
        } catch (RuntimeException exception) {
            throw new SnapshotStoreException(
                    SnapshotStoreException.Reason.UNSUPPORTED_SCHEMA,
                    "snapshot container does not use a canonical immutable id",
                    exception
            );
        }
    }

    private Path payloadPath(Path containerPath) {
        Path normalizedContainer = containerPath.toAbsolutePath().normalize();
        Path payload = normalizedContainer.resolve(SnapshotManifestCodec.PAYLOAD_DIRECTORY).normalize();
        ensureDirectChild(normalizedContainer, payload, "Snapshot payload must be a direct child of its container");
        return payload;
    }

    private Runnable effectiveContinuationCheck(Runnable continuationCheck) {
        return continuationCheck == null ? () -> {
        } : continuationCheck;
    }

    private void cleanupOwnedDirectory(Path directory,
                                       Throwable operationFailure,
                                       boolean committedResult,
                                       String cleanupKind) throws IOException {
        try {
            workspaceFileSystemService.deleteDirectory(directory);
        } catch (IOException cleanupFailure) {
            if (operationFailure != null) {
                operationFailure.addSuppressed(cleanupFailure);
                return;
            }
            if (committedResult) {
                // 最终容器已验证提交，临时目录清理失败不应把已知成功改写成未知结果。
                log.warn("Failed to clean committed snapshot {}, exceptionType: {}",
                        cleanupKind, cleanupFailure.getClass().getSimpleName());
                return;
            }
            throw cleanupFailure;
        }
    }

    private ReentrantLock snapshotLockFor(Path snapshotPath) {
        int index = Math.floorMod(
                snapshotPath.toAbsolutePath().normalize().toString().hashCode(),
                snapshotLocks.length
        );
        return snapshotLocks[index];
    }

    private ReentrantLock[] createSnapshotLocks() {
        ReentrantLock[] locks = new ReentrantLock[SNAPSHOT_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private void validateExistingDirectory(Path directory, String message) {
        try {
            workspaceFileSystemService.isDirectory(directory);
        } catch (IOException exception) {
            throw storageFailure(message, exception);
        }
    }

    private void ensureDirectChild(Path root, Path child, String message) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (normalizedChild.equals(normalizedRoot)
                || normalizedChild.getParent() == null
                || !normalizedChild.getParent().equals(normalizedRoot)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, message);
        }
    }

    private void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Application id must be positive");
        }
    }

    private BusinessException storageFailure(String message, IOException exception) {
        ErrorCode errorCode = exception instanceof WorkspaceFileSystemException workspaceException
                && workspaceException.reason() == WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK
                ? ErrorCode.NO_AUTH_ERROR
                : ErrorCode.SYSTEM_ERROR;
        return new BusinessException(errorCode, message, exception);
    }
}
