package com.rush.rushaicodemother.orchestration.dag;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.runtime.identity.UuidGenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 版本化的编排检查点存储。
 *
 * <p>Production可以通过{@link GenerationOrchestrationCheckpointRepository}持久化检查点。
 * 本地文件实现仍然作为测试和单节点诊断的有限回退。</p>
 */
@Slf4j
@Component
public class GenerationOrchestrationTaskStore {

    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final String SNAPSHOT_SUFFIX = ".json";

    private final GenerationTaskSnapshotProperties properties;
    private final GenerationTaskIdGenerator taskIdGenerator;
    private final GenerationOrchestrationCheckpointRepository checkpointRepository;
    private final GenerationExecutionContextService executionContextService;
    private final Path rootDirectory;
    private final ReentrantLock[] writeLocks;

    public GenerationOrchestrationTaskStore(GenerationTaskSnapshotProperties properties) {
        this(properties, new UuidGenerationTaskIdGenerator(), null, null);
    }

    public GenerationOrchestrationTaskStore(GenerationTaskSnapshotProperties properties,
                                            GenerationTaskIdGenerator taskIdGenerator) {
        this(properties, taskIdGenerator, null, null);
    }

    public GenerationOrchestrationTaskStore(
            GenerationTaskSnapshotProperties properties,
            GenerationTaskIdGenerator taskIdGenerator,
            GenerationOrchestrationCheckpointRepository checkpointRepository) {
        this(properties, taskIdGenerator, checkpointRepository, null);
    }

    @Autowired
    public GenerationOrchestrationTaskStore(
            GenerationTaskSnapshotProperties properties,
            GenerationTaskIdGenerator taskIdGenerator,
            GenerationOrchestrationCheckpointRepository checkpointRepository,
            GenerationExecutionContextService executionContextService) {
        this.properties = properties;
        this.taskIdGenerator = taskIdGenerator;
        this.checkpointRepository = checkpointRepository;
        this.executionContextService = executionContextService;
        this.rootDirectory = properties.getRootDirectory().toAbsolutePath().normalize();
        this.writeLocks = new ReentrantLock[properties.getLockStripes()];
        for (int index = 0; index < writeLocks.length; index++) {
            writeLocks[index] = new ReentrantLock();
        }
    }

    public GenerationOrchestrationTask create(Long appId, String userMessage) {
        return create(taskIdGenerator.nextId(), appId, userMessage);
    }

    public GenerationOrchestrationTask create(String taskId, Long appId, String userMessage) {
        String normalizedTaskId = StrUtil.trim(taskId);
        if (!isSafeTaskId(normalizedTaskId)) {
            throw new IllegalArgumentException("invalid orchestration task id");
        }
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId(normalizedTaskId);
        task.setExecutionEpoch(currentExecutionEpoch(normalizedTaskId));
        task.setAppId(appId);
        task.setRequestHash(buildRequestHash(userMessage));
        task.setStatus("running");
        save(task);
        return task;
    }

    public void save(GenerationOrchestrationTask task) {
        if (task == null) {
            throw new IllegalArgumentException("orchestration task is required");
        }
        task.setUpdatedAt(LocalDateTime.now());
        task.setExecutionEpoch(Math.max(task.getExecutionEpoch(), currentExecutionEpoch(task.getTaskId())));
        if (!properties.isEnabled()) {
            return;
        }
        Long appId = task.getAppId();
        String taskId = StrUtil.trim(task.getTaskId());
        if (!isValidIdentity(appId, taskId)) {
            throw new GenerationCheckpointPersistenceException(
                    GenerationCheckpointPersistenceException.Reason.INVALID_IDENTITY,
                    "orchestration checkpoint identity is invalid");
        }

        ReentrantLock lock = lockFor(appId);
        lock.lock();
        try {
            String snapshotJson = JSONUtil.toJsonPrettyStr(task);
            byte[] snapshotBytes = snapshotJson.getBytes(StandardCharsets.UTF_8);
            if (snapshotBytes.length > properties.getMaxSnapshotBytes()) {
                throw new GenerationCheckpointPersistenceException(
                        GenerationCheckpointPersistenceException.Reason.SNAPSHOT_TOO_LARGE,
                        "orchestration checkpoint exceeds the configured size limit");
            } else if (checkpointRepository != null) {
                checkpointRepository.save(task, snapshotJson, snapshotBytes.length);
            } else {
                Path taskFile = resolveTaskPath(appId, taskId);
                Files.createDirectories(taskFile.getParent());
                writeAtomically(taskFile, snapshotBytes);
                cleanupSnapshots(taskFile.getParent());
            }
        } catch (GenerationCheckpointPersistenceException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Failed to save orchestration checkpoint, taskId: {}",
                    taskId, LogExceptionSanitizer.sanitize(exception));
            throw new GenerationCheckpointPersistenceException(
                    GenerationCheckpointPersistenceException.Reason.STORAGE_FAILURE,
                    "orchestration checkpoint could not be durably committed",
                    exception);
        } finally {
            lock.unlock();
        }
    }

    public Optional<GenerationOrchestrationTask> load(Long appId, String taskId) {
        String normalizedTaskId = StrUtil.trim(taskId);
        if (!isValidIdentity(appId, normalizedTaskId)) {
            throw new IllegalArgumentException("invalid orchestration task identity");
        }
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        if (checkpointRepository != null) {
            try {
                Optional<String> payload = checkpointRepository.loadPayload(appId, normalizedTaskId);
                if (payload.isEmpty()) {
                    return Optional.empty();
                }
                GenerationOrchestrationTask task = parseTask(payload.get());
                validateLoadedTask(task, appId, normalizedTaskId);
                normalizeLoadedTask(task);
                return Optional.of(task);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("unable to load orchestration checkpoint", exception);
            }
        }

        Path taskFile = resolveTaskPath(appId, normalizedTaskId);
        ReentrantLock lock = lockFor(appId);
        lock.lock();
        try {
            if (!Files.exists(taskFile, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(taskFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("orchestration checkpoint is not a regular file");
            }
            long size = Files.size(taskFile);
            if (size <= 0 || size > properties.getMaxSnapshotBytes()) {
                throw new IllegalStateException("orchestration checkpoint size is invalid");
            }
            GenerationOrchestrationTask task = parseTask(Files.readString(taskFile, StandardCharsets.UTF_8));
            validateLoadedTask(task, appId, normalizedTaskId);
            normalizeLoadedTask(task);
            return Optional.of(task);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("unable to load orchestration checkpoint", exception);
        } finally {
            lock.unlock();
        }
    }

    public boolean matchesRequest(GenerationOrchestrationTask task, String userMessage) {
        return task != null && buildRequestHash(userMessage).equals(task.getRequestHash());
    }

    Path resolveTaskPath(Long appId, String taskId) {
        if (!isValidIdentity(appId, taskId)) {
            throw new IllegalArgumentException("invalid orchestration task identity");
        }
        Path appDirectory = rootDirectory.resolve("app_" + appId).normalize();
        Path taskFile = appDirectory.resolve(taskId + SNAPSHOT_SUFFIX).normalize();
        if (!appDirectory.startsWith(rootDirectory) || !taskFile.startsWith(appDirectory)) {
            throw new IllegalArgumentException("orchestration task snapshot path escapes configured root");
        }
        return taskFile;
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporaryFile = Files.createTempFile(target.getParent(), ".snapshot-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporaryFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporaryFile, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private long currentExecutionEpoch(String taskId) {
        if (executionContextService == null || taskId == null) {
            return 0L;
        }
        return executionContextService.getExecutionFence(taskId)
                .map(fence -> fence.executionEpoch())
                .orElse(0L);
    }

    private GenerationOrchestrationTask parseTask(String payloadJson) {
        return JSONUtil.toBean(payloadJson, GenerationOrchestrationTask.class);
    }

    private void validateLoadedTask(GenerationOrchestrationTask task, Long appId, String taskId) {
        if (task == null
                || !GenerationOrchestrationTask.supportsSchemaVersion(task.getSchemaVersion())
                || !appId.equals(task.getAppId())
                || !taskId.equals(task.getTaskId())
                || StrUtil.isBlank(task.getRequestHash())) {
            throw new IllegalStateException("orchestration checkpoint identity or schema is invalid");
        }
    }

    private void normalizeLoadedTask(GenerationOrchestrationTask task) {
        if (task.getRuntimeState() == null) {
            task.setRuntimeState(AgentRuntimeState.INITIALIZED);
        }
        if (task.getNodeStatuses() == null) {
            task.setNodeStatuses(new java.util.LinkedHashMap<>());
        }
        if (task.getTimings() == null) {
            task.setTimings(new java.util.LinkedHashMap<>());
        }
        if (task.getArtifacts() == null) {
            task.setArtifacts(new java.util.LinkedHashMap<>());
        }
        if (task.getEvents() == null) {
            task.setEvents(new ArrayList<>());
        }
    }

    private void cleanupSnapshots(Path appDirectory) throws IOException {
        if (!Files.isDirectory(appDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant expirationCutoff = Instant.now().minus(properties.getRetention());
        List<SnapshotFile> retainedFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.list(appDirectory)) {
            for (Path path : paths.toList()) {
                SnapshotFile snapshotFile = inspectSnapshot(path);
                if (snapshotFile == null) {
                    continue;
                }
                if (snapshotFile.lastModified().toInstant().isBefore(expirationCutoff)) {
                    Files.deleteIfExists(snapshotFile.path());
                } else {
                    retainedFiles.add(snapshotFile);
                }
            }
        }
        retainedFiles.sort(Comparator
                .comparing(SnapshotFile::lastModified)
                .reversed()
                .thenComparing(snapshot -> snapshot.path().getFileName().toString()));
        for (int index = properties.getMaxSnapshotsPerApp(); index < retainedFiles.size(); index++) {
            Files.deleteIfExists(retainedFiles.get(index).path());
        }
    }

    private SnapshotFile inspectSnapshot(Path path) {
        if (path == null
                || !path.getFileName().toString().endsWith(SNAPSHOT_SUFFIX)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return new SnapshotFile(path, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            log.warn("Failed to inspect orchestration checkpoint metadata, fileName: {}",
                    path.getFileName(), LogExceptionSanitizer.sanitize(exception));
            return null;
        }
    }

    private ReentrantLock lockFor(Long appId) {
        return writeLocks[Math.floorMod(Long.hashCode(appId), writeLocks.length)];
    }

    private boolean isValidIdentity(Long appId, String taskId) {
        return appId != null && appId > 0 && isSafeTaskId(taskId);
    }

    private boolean isSafeTaskId(String taskId) {
        return taskId != null && SAFE_TASK_ID.matcher(taskId).matches();
    }

    private String buildRequestHash(String userMessage) {
        return DigestUtil.sha256Hex(StrUtil.blankToDefault(userMessage, ""));
    }

    private record SnapshotFile(Path path, FileTime lastModified) {
    }
}
