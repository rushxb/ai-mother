package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 编辑状态服务。
 *
 * <p>仅持久化连续改修所需的任务标识、文件路径、结果状态和时间戳；用户消息、失败原因及
 * 验证详情不进入本地状态文件。状态采用不可变快照，并在同一应用的条带锁内完成读取、更新、
 * 原子保存和缓存替换，避免并发写入丢失。</p>
 */
@Slf4j
@Service
public class EditStatePersistenceService {

    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern SAFE_STATUS = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final EditStatePersistenceProperties properties;
    private final LocalEditStateStore stateStore;
    private final Cache<Long, EditStateSnapshot> cache;
    private final ReentrantLock[] stateLocks;
    private final Clock clock;

    @Autowired
    public EditStatePersistenceService(EditStatePersistenceProperties properties,
                                       LocalEditStateStore stateStore) {
        this(properties, stateStore, Clock.systemUTC());
    }

    EditStatePersistenceService(EditStatePersistenceProperties properties,
                                LocalEditStateStore stateStore,
                                Clock clock) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCacheEntries())
                .expireAfterAccess(properties.getCacheExpireAfterAccess())
                .build();
        this.stateLocks = new ReentrantLock[properties.getLockStripes()];
        for (int index = 0; index < stateLocks.length; index++) {
            stateLocks[index] = new ReentrantLock();
        }
    }

    /**
     * 记录一次编辑结果。无效身份或路径会被忽略，存储失败不会中断编辑主流程。
     */
    public void recordEditResult(Long appId,
                                 String taskId,
                                 List<PatchOperation> patchOperations,
                                 boolean success) {
        String normalizedTaskId = normalizeTaskId(taskId);
        if (!isValidAppId(appId) || normalizedTaskId == null) {
            return;
        }
        List<String> changedFiles = extractChangedFiles(patchOperations);
        long nowEpochMillis = clock.millis();

        ReentrantLock lock = lockFor(appId);
        lock.lock();
        try {
            EditStateSnapshot current = loadState(appId);
            List<EditStateSnapshot.EditRecord> recentEdits = prependBounded(
                    current.recentEdits(),
                    new EditStateSnapshot.EditRecord(normalizedTaskId, success, nowEpochMillis),
                    properties.getMaxRecentEdits()
            );
            List<EditStateSnapshot.RecentFile> recentFiles = updateRecentFiles(
                    current.recentFiles(), changedFiles, success, nowEpochMillis);
            persistAndCache(appId, new EditStateSnapshot(
                    EditStateSnapshot.CURRENT_SCHEMA_VERSION,
                    recentEdits,
                    recentFiles,
                    current.recentValidations(),
                    nowEpochMillis
            ));
            log.debug("记录编辑结果，appId: {}, taskId: {}, success: {}", appId, normalizedTaskId, success);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 记录后台验证的状态分类，不保存验证消息或明细。
     */
    public void recordValidationResult(Long appId, String taskId, String status) {
        String normalizedTaskId = normalizeTaskId(taskId);
        if (!isValidAppId(appId) || normalizedTaskId == null) {
            return;
        }
        String normalizedStatus = normalizeStatus(status);
        long nowEpochMillis = clock.millis();

        ReentrantLock lock = lockFor(appId);
        lock.lock();
        try {
            EditStateSnapshot current = loadState(appId);
            List<EditStateSnapshot.ValidationRecord> recentValidations = prependBounded(
                    current.recentValidations(),
                    new EditStateSnapshot.ValidationRecord(normalizedTaskId, normalizedStatus, nowEpochMillis),
                    properties.getMaxRecentValidations()
            );
            persistAndCache(appId, new EditStateSnapshot(
                    EditStateSnapshot.CURRENT_SCHEMA_VERSION,
                    current.recentEdits(),
                    current.recentFiles(),
                    recentValidations,
                    nowEpochMillis
            ));
            log.debug("记录验证结果，appId: {}, taskId: {}, status: {}",
                    appId, normalizedTaskId, normalizedStatus);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取最近成功修改的文件。
     */
    public List<String> getRecentModifiedFiles(Long appId, int limit) {
        if (!isValidAppId(appId) || limit <= 0) {
            return List.of();
        }
        EditStateSnapshot state = readState(appId);
        return state.recentFiles().stream()
                .filter(EditStateSnapshot.RecentFile::success)
                .map(EditStateSnapshot.RecentFile::filePath)
                .limit(Math.min(limit, properties.getMaxRecentFiles()))
                .toList();
    }

    /**
     * 使用当前用户消息匹配最近成功修改的文件；历史用户消息不会被持久化。
     */
    public List<String> getRelevantRecentFiles(Long appId, String userMessage, int limit) {
        if (!isValidAppId(appId) || StrUtil.isBlank(userMessage) || limit <= 0) {
            return List.of();
        }
        String normalizedMessage = userMessage.toLowerCase(Locale.ROOT).replace('\\', '/');
        EditStateSnapshot state = readState(appId);
        return state.recentFiles().stream()
                .filter(EditStateSnapshot.RecentFile::success)
                .filter(file -> isRelevantToMessage(file.filePath(), normalizedMessage))
                .map(EditStateSnapshot.RecentFile::filePath)
                .limit(Math.min(limit, properties.getMaxRecentFiles()))
                .toList();
    }

    /**
     * 判断文件是否在指定时间窗口内成功修改过。
     */
    public boolean wasRecentlyModified(Long appId, String filePath, long withinHours) {
        String normalizedFilePath = normalizeFilePath(filePath);
        if (!isValidAppId(appId) || normalizedFilePath == null || withinHours <= 0) {
            return false;
        }
        long retentionHours = Math.max(1, properties.getStateRetention().toHours());
        long boundedHours = Math.min(withinHours, retentionHours);
        Instant cutoff = clock.instant().minus(Duration.ofHours(boundedHours));
        EditStateSnapshot state = readState(appId);
        return state.recentFiles().stream()
                .anyMatch(file -> file.success()
                        && file.filePath().equals(normalizedFilePath)
                        && Instant.ofEpochMilli(file.lastModifiedEpochMillis()).isAfter(cutoff));
    }

    long estimatedCacheSize() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private EditStateSnapshot readState(Long appId) {
        ReentrantLock lock = lockFor(appId);
        lock.lock();
        try {
            return loadState(appId);
        } finally {
            lock.unlock();
        }
    }

    private EditStateSnapshot loadState(Long appId) {
        EditStateSnapshot cached = cache.getIfPresent(appId);
        if (cached != null) {
            return cached;
        }
        EditStateSnapshot loaded = stateStore.load(appId);
        EditStateSnapshot normalized = normalizeLoadedState(loaded);
        cache.put(appId, normalized);
        if (!normalized.equals(loaded)) {
            stateStore.save(appId, normalized);
        }
        return normalized;
    }

    private EditStateSnapshot normalizeLoadedState(EditStateSnapshot loaded) {
        long nowEpochMillis = clock.millis();
        if (loaded == null || loaded.schemaVersion() != EditStateSnapshot.CURRENT_SCHEMA_VERSION) {
            return EditStateSnapshot.empty(nowEpochMillis);
        }

        List<EditStateSnapshot.EditRecord> recentEdits = loaded.recentEdits().stream()
                .filter(record -> record != null && normalizeTaskId(record.taskId()) != null)
                .map(record -> new EditStateSnapshot.EditRecord(
                        normalizeTaskId(record.taskId()),
                        record.success(),
                        normalizeTimestamp(record.timestampEpochMillis(), nowEpochMillis)))
                .limit(properties.getMaxRecentEdits())
                .toList();

        Set<String> seenFiles = new HashSet<>();
        List<EditStateSnapshot.RecentFile> recentFiles = loaded.recentFiles().stream()
                .filter(file -> file != null)
                .map(file -> {
                    String normalizedPath = normalizeFilePath(file.filePath());
                    if (normalizedPath == null || !seenFiles.add(normalizedPath)) {
                        return null;
                    }
                    return new EditStateSnapshot.RecentFile(
                            normalizedPath,
                            normalizeTimestamp(file.lastModifiedEpochMillis(), nowEpochMillis),
                            file.success());
                })
                .filter(file -> file != null)
                .limit(properties.getMaxRecentFiles())
                .toList();

        List<EditStateSnapshot.ValidationRecord> recentValidations = loaded.recentValidations().stream()
                .filter(record -> record != null && normalizeTaskId(record.taskId()) != null)
                .map(record -> new EditStateSnapshot.ValidationRecord(
                        normalizeTaskId(record.taskId()),
                        normalizeStatus(record.status()),
                        normalizeTimestamp(record.timestampEpochMillis(), nowEpochMillis)))
                .limit(properties.getMaxRecentValidations())
                .toList();

        return new EditStateSnapshot(
                EditStateSnapshot.CURRENT_SCHEMA_VERSION,
                recentEdits,
                recentFiles,
                recentValidations,
                normalizeTimestamp(loaded.updatedAtEpochMillis(), nowEpochMillis)
        );
    }

    private List<EditStateSnapshot.RecentFile> updateRecentFiles(
            List<EditStateSnapshot.RecentFile> currentFiles,
            List<String> changedFiles,
            boolean success,
            long nowEpochMillis) {
        List<EditStateSnapshot.RecentFile> updated = new ArrayList<>(currentFiles);
        for (String filePath : changedFiles) {
            updated.removeIf(file -> file.filePath().equals(filePath));
            updated.add(0, new EditStateSnapshot.RecentFile(filePath, nowEpochMillis, success));
        }
        if (updated.size() > properties.getMaxRecentFiles()) {
            return List.copyOf(updated.subList(0, properties.getMaxRecentFiles()));
        }
        return List.copyOf(updated);
    }

    private List<String> extractChangedFiles(List<PatchOperation> patchOperations) {
        if (patchOperations == null || patchOperations.isEmpty()) {
            return List.of();
        }
        Set<String> seenPaths = new HashSet<>();
        List<String> changedFiles = new ArrayList<>();
        for (PatchOperation operation : patchOperations) {
            if (operation == null) {
                continue;
            }
            String normalizedPath = normalizeFilePath(operation.relativePath());
            if (normalizedPath != null && seenPaths.add(normalizedPath)) {
                changedFiles.add(normalizedPath);
                if (changedFiles.size() >= properties.getMaxRecentFiles()) {
                    break;
                }
            }
        }
        return List.copyOf(changedFiles);
    }

    private String normalizeTaskId(String taskId) {
        String normalized = StrUtil.trim(taskId);
        if (StrUtil.isBlank(normalized)
                || normalized.length() > properties.getMaxTaskIdLength()
                || !SAFE_TASK_ID.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private String normalizeFilePath(String filePath) {
        String normalized = StrUtil.trim(filePath);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        normalized = normalized.replace('\\', '/');
        if (normalized.length() > properties.getMaxFilePathLength()
                || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null;
            }
            segments.add(segment);
        }
        String result = String.join("/", segments);
        return result.isBlank() || result.length() > properties.getMaxFilePathLength() ? null : result;
    }

    private String normalizeStatus(String status) {
        String normalized = StrUtil.blankToDefault(status, "unknown").trim().toLowerCase(Locale.ROOT);
        return SAFE_STATUS.matcher(normalized).matches() ? normalized : "unknown";
    }

    private boolean isRelevantToMessage(String filePath, String normalizedMessage) {
        String normalizedPath = filePath.toLowerCase(Locale.ROOT);
        int separatorIndex = normalizedPath.lastIndexOf('/');
        String fileName = separatorIndex >= 0 ? normalizedPath.substring(separatorIndex + 1) : normalizedPath;
        int extensionIndex = fileName.lastIndexOf('.');
        String fileNameWithoutExtension = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        return (!fileNameWithoutExtension.isBlank() && normalizedMessage.contains(fileNameWithoutExtension))
                || normalizedMessage.contains(normalizedPath);
    }

    private long normalizeTimestamp(long timestampEpochMillis, long nowEpochMillis) {
        long maximumAcceptedTimestamp = nowEpochMillis + MAX_CLOCK_SKEW.toMillis();
        if (timestampEpochMillis <= 0 || timestampEpochMillis > maximumAcceptedTimestamp) {
            return nowEpochMillis;
        }
        return timestampEpochMillis;
    }

    private <T> List<T> prependBounded(List<T> current, T value, int maximumSize) {
        List<T> updated = new ArrayList<>(Math.min(current.size() + 1, maximumSize));
        updated.add(value);
        int retainedCurrentItems = Math.min(current.size(), maximumSize - 1);
        updated.addAll(current.subList(0, retainedCurrentItems));
        return List.copyOf(updated);
    }

    private void persistAndCache(Long appId, EditStateSnapshot state) {
        cache.put(appId, state);
        stateStore.save(appId, state);
    }

    private ReentrantLock lockFor(Long appId) {
        return stateLocks[Math.floorMod(Long.hashCode(appId), stateLocks.length)];
    }

    private boolean isValidAppId(Long appId) {
        return appId != null && appId > 0;
    }
}
