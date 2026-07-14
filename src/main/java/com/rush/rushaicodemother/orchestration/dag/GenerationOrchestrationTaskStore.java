package com.rush.rushaicodemother.orchestration.dag;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bounded, best-effort local persistence for orchestration task diagnostics.
 * Snapshot failures never fail the generation workflow, but writes are atomic and retained data is bounded.
 */
@Slf4j
@Component
public class GenerationOrchestrationTaskStore {

    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final String SNAPSHOT_SUFFIX = ".json";

    private final GenerationTaskSnapshotProperties properties;
    private final Path rootDirectory;
    private final ReentrantLock[] writeLocks;

    public GenerationOrchestrationTaskStore(GenerationTaskSnapshotProperties properties) {
        this.properties = properties;
        this.rootDirectory = properties.getRootDirectory().toAbsolutePath().normalize();
        this.writeLocks = new ReentrantLock[properties.getLockStripes()];
        for (int index = 0; index < writeLocks.length; index++) {
            writeLocks[index] = new ReentrantLock();
        }
    }

    public GenerationOrchestrationTask create(Long appId, String userMessage) {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId(IdUtil.fastSimpleUUID());
        task.setAppId(appId);
        task.setRequestHash(buildRequestHash(userMessage));
        task.setStatus("running");
        save(task);
        return task;
    }

    public void save(GenerationOrchestrationTask task) {
        if (task == null) {
            return;
        }
        task.setUpdatedAt(LocalDateTime.now());
        if (!properties.isEnabled()) {
            return;
        }
        Long appId = task.getAppId();
        String taskId = StrUtil.trim(task.getTaskId());
        if (!isValidIdentity(appId, taskId)) {
            log.warn("跳过无效编排任务快照，appId: {}, taskId: {}", appId, taskId);
            return;
        }

        ReentrantLock lock = lockFor(appId);
        lock.lock();
        try {
            Path taskFile = resolveTaskPath(appId, taskId);
            Files.createDirectories(taskFile.getParent());
            byte[] snapshotBytes = JSONUtil.toJsonPrettyStr(task).getBytes(StandardCharsets.UTF_8);
            if (snapshotBytes.length > properties.getMaxSnapshotBytes()) {
                Files.deleteIfExists(taskFile);
                log.warn("编排任务快照超过持久化上限，已跳过写入，taskId: {}, bytes: {}, maxBytes: {}",
                        taskId, snapshotBytes.length, properties.getMaxSnapshotBytes());
            } else {
                writeAtomically(taskFile, snapshotBytes);
            }
            cleanupSnapshots(taskFile.getParent());
        } catch (Exception exception) {
            log.warn("保存编排任务快照失败，taskId: {}", taskId, LogExceptionSanitizer.sanitize(exception));
        } finally {
            lock.unlock();
        }
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
            log.warn("读取编排任务快照元数据失败，fileName: {}",
                    path.getFileName(), LogExceptionSanitizer.sanitize(exception));
            return null;
        }
    }

    private ReentrantLock lockFor(Long appId) {
        return writeLocks[Math.floorMod(Long.hashCode(appId), writeLocks.length)];
    }

    private boolean isValidIdentity(Long appId, String taskId) {
        return appId != null && appId > 0 && StrUtil.isNotBlank(taskId) && SAFE_TASK_ID.matcher(taskId).matches();
    }

    private String buildRequestHash(String userMessage) {
        return DigestUtil.sha256Hex(StrUtil.blankToDefault(userMessage, ""));
    }

    private record SnapshotFile(Path path, FileTime lastModified) {
    }
}
