package com.rush.rushaicodemother.orchestration.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 编辑状态的有界本地文件存储。
 *
 * <p>写入使用同目录临时文件、强制刷盘和原子替换；读取受文件大小与保留期限约束。
 * 任何存储故障均按最佳努力处理，不会中断编辑主流程。</p>
 */
@Slf4j
@Component
public class LocalEditStateStore {

    private static final Pattern STATE_FILE_PATTERN = Pattern.compile("app_[1-9][0-9]*\\.json");
    private static final String STATE_FILE_PREFIX = "app_";
    private static final String STATE_FILE_SUFFIX = ".json";
    private static final String TEMP_FILE_PREFIX = ".edit-state-";
    private static final String TEMP_FILE_SUFFIX = ".tmp";

    private final EditStatePersistenceProperties properties;
    private final ObjectMapper objectMapper;
    private final Path rootDirectory;
    private final Clock clock;
    private final ReentrantReadWriteLock persistenceLifecycleLock = new ReentrantReadWriteLock();
    private final ReentrantLock cleanupLock = new ReentrantLock();

    @Autowired
    public LocalEditStateStore(EditStatePersistenceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    LocalEditStateStore(EditStatePersistenceProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.rootDirectory = properties.getRootDirectory().toAbsolutePath().normalize();
        this.clock = clock;
    }

    EditStateSnapshot load(Long appId) {
        long nowEpochMillis = clock.millis();
        if (!properties.isEnabled() || !isValidAppId(appId)) {
            return EditStateSnapshot.empty(nowEpochMillis);
        }

        if (!isSafeRootDirectory()) {
            log.warn("编辑状态根目录不是普通目录，已跳过读取: {}", rootDirectory);
            return EditStateSnapshot.empty(nowEpochMillis);
        }
        Path stateFile = resolveStatePath(appId);
        ReentrantReadWriteLock.ReadLock readLock = persistenceLifecycleLock.readLock();
        readLock.lock();
        try {
            if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                return EditStateSnapshot.empty(nowEpochMillis);
            }
            FileTime lastModified = Files.getLastModifiedTime(stateFile, LinkOption.NOFOLLOW_LINKS);
            if (lastModified.toInstant().isBefore(clock.instant().minus(properties.getStateRetention()))) {
                Files.deleteIfExists(stateFile);
                return EditStateSnapshot.empty(nowEpochMillis);
            }

            byte[] content = readBounded(stateFile);
            if (content.length == 0) {
                deleteInvalidStateFile(stateFile, appId, "empty state file");
                return EditStateSnapshot.empty(nowEpochMillis);
            }
            return deserializeState(content, stateFile, appId, nowEpochMillis);
        } catch (StateFileTooLargeException exception) {
            deleteInvalidStateFile(stateFile, appId, exception.getMessage());
            return EditStateSnapshot.empty(nowEpochMillis);
        } catch (IOException exception) {
            log.warn("读取编辑状态失败，appId: {}", appId, LogExceptionSanitizer.sanitize(exception));
            return EditStateSnapshot.empty(nowEpochMillis);
        } finally {
            readLock.unlock();
        }
    }

    boolean save(Long appId, EditStateSnapshot snapshot) {
        if (!properties.isEnabled() || !isValidAppId(appId) || snapshot == null) {
            return false;
        }

        byte[] content;
        try {
            content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
        } catch (IOException exception) {
            log.warn("序列化编辑状态失败，appId: {}", appId, LogExceptionSanitizer.sanitize(exception));
            return false;
        }
        if (content.length > properties.getMaxStateFileBytes()) {
            log.warn("编辑状态超过持久化上限，已保留上一份有效状态，appId: {}, bytes: {}, maxBytes: {}",
                    appId, content.length, properties.getMaxStateFileBytes());
            return false;
        }

        Path stateFile = resolveStatePath(appId);
        ReentrantReadWriteLock.ReadLock readLock = persistenceLifecycleLock.readLock();
        readLock.lock();
        try {
            Files.createDirectories(rootDirectory);
            if (!isSafeRootDirectory()) {
                log.warn("编辑状态根目录不是普通目录，已跳过写入: {}", rootDirectory);
                return false;
            }
            writeAtomically(stateFile, content);
        } catch (IOException exception) {
            log.warn("保存编辑状态失败，appId: {}", appId, LogExceptionSanitizer.sanitize(exception));
            return false;
        } finally {
            readLock.unlock();
        }
        cleanupPersistedStates();
        return true;
    }

    Path resolveStatePath(Long appId) {
        if (!isValidAppId(appId)) {
            throw new IllegalArgumentException("invalid edit-state app identity");
        }
        Path stateFile = rootDirectory.resolve(STATE_FILE_PREFIX + appId + STATE_FILE_SUFFIX).normalize();
        if (!stateFile.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("edit-state path escapes configured root");
        }
        return stateFile;
    }

    private EditStateSnapshot deserializeState(byte[] content,
                                                       Path stateFile,
                                                       Long appId,
                                                       long nowEpochMillis) {
        try {
            EditStateSnapshot snapshot = objectMapper.readValue(content, EditStateSnapshot.class);
            if (snapshot.schemaVersion() != EditStateSnapshot.CURRENT_SCHEMA_VERSION) {
                deleteInvalidStateFile(stateFile, appId, "unsupported schema version");
                return EditStateSnapshot.empty(nowEpochMillis);
            }
            return snapshot;
        } catch (Exception exception) {
            deleteInvalidStateFile(stateFile, appId, "invalid state content");
            log.warn("解析编辑状态失败，appId: {}", appId, LogExceptionSanitizer.sanitize(exception));
            return EditStateSnapshot.empty(nowEpochMillis);
        }
    }

    private byte[] readBounded(Path stateFile) throws IOException {
        try (FileChannel channel = FileChannel.open(
                stateFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long fileSize = channel.size();
            if (fileSize > properties.getMaxStateFileBytes()) {
                throw new StateFileTooLargeException(
                        "state file exceeds " + properties.getMaxStateFileBytes() + " bytes");
            }
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(fileSize));
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
            }
            if (!buffer.hasRemaining() && channel.read(ByteBuffer.allocate(1)) > 0) {
                throw new StateFileTooLargeException("state file changed while being read");
            }
            return buffer.array();
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporaryFile = Files.createTempFile(rootDirectory, TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
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

    private void cleanupPersistedStates() {
        if (!cleanupLock.tryLock()) {
            return;
        }
        ReentrantReadWriteLock.WriteLock writeLock = persistenceLifecycleLock.writeLock();
        writeLock.lock();
        try {
            if (!Files.isDirectory(rootDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Instant expirationCutoff = clock.instant().minus(properties.getStateRetention());
            List<StateFile> retainedFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.list(rootDirectory)) {
                for (Path path : paths.toList()) {
                    String fileName = path.getFileName().toString();
                    if (fileName.startsWith(TEMP_FILE_PREFIX) && fileName.endsWith(TEMP_FILE_SUFFIX)) {
                        Files.deleteIfExists(path);
                        continue;
                    }
                    StateFile stateFile = inspectStateFile(path);
                    if (stateFile == null) {
                        continue;
                    }
                    if (stateFile.lastModified().toInstant().isBefore(expirationCutoff)) {
                        Files.deleteIfExists(stateFile.path());
                    } else {
                        retainedFiles.add(stateFile);
                    }
                }
            }
            retainedFiles.sort(Comparator
                    .comparing(StateFile::lastModified)
                    .reversed()
                    .thenComparing(stateFile -> stateFile.path().getFileName().toString()));
            for (int index = properties.getMaxPersistedApps(); index < retainedFiles.size(); index++) {
                Files.deleteIfExists(retainedFiles.get(index).path());
            }
        } catch (IOException exception) {
            log.warn("清理编辑状态文件失败", LogExceptionSanitizer.sanitize(exception));
        } finally {
            writeLock.unlock();
            cleanupLock.unlock();
        }
    }

    private StateFile inspectStateFile(Path path) {
        if (path == null
                || !STATE_FILE_PATTERN.matcher(path.getFileName().toString()).matches()
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return new StateFile(path, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            log.warn("读取编辑状态文件元数据失败，fileName: {}",
                    path.getFileName(), LogExceptionSanitizer.sanitize(exception));
            return null;
        }
    }

    private void deleteInvalidStateFile(Path stateFile, Long appId, String reason) {
        try {
            Files.deleteIfExists(stateFile);
            log.warn("已删除无效编辑状态，appId: {}, reason: {}", appId, reason);
        } catch (IOException deleteFailure) {
            log.warn("删除无效编辑状态失败，appId: {}", appId, LogExceptionSanitizer.sanitize(deleteFailure));
        }
    }

    private boolean isSafeRootDirectory() {
        return !Files.exists(rootDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(rootDirectory, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isValidAppId(Long appId) {
        return appId != null && appId > 0;
    }

    private record StateFile(Path path, FileTime lastModified) {
    }

    private static final class StateFileTooLargeException extends IOException {
        private StateFileTooLargeException(String message) {
            super(message);
        }
    }
}
