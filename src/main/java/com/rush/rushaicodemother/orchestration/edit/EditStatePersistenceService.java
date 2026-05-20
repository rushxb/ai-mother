package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 编辑状态持久化服务。
 * 记录最近修改文件、最近 patch 成功/失败原因、最近 build 结果、最近用户意图和命中文件。
 * 连续改修时优先召回上次相关文件，减少重新定位成本。
 */
@Slf4j
@Service
public class EditStatePersistenceService {

    private static final String STATE_DIRECTORY_NAME = ".ai-code-edit-state";
    private static final String STATE_FILE_NAME = "edit-state.json";
    private static final int MAX_RECENT_EDITS = 20;
    private static final int MAX_RECENT_FILES = 50;
    private static final long STATE_EXPIRY_HOURS = 24;

    private final ConcurrentMap<String, EditState> cache = new ConcurrentHashMap<>();

    /**
     * 记录编辑结果。
     *
     * @param appId          应用 ID
     * @param taskId         任务 ID
     * @param userMessage    用户消息
     * @param patchOperations 补丁操作列表
     * @param success        是否成功
     * @param reason         原因（失败时）
     */
    public void recordEditResult(Long appId, String taskId, String userMessage, List<PatchOperation> patchOperations, boolean success, String reason) {
        if (appId == null) {
            return;
        }
        EditState state = getOrCreateState(appId);
        EditRecord record = new EditRecord(
                taskId,
                userMessage,
                patchOperations.stream().map(PatchOperation::relativePath).filter(StrUtil::isNotBlank).toList(),
                success,
                StrUtil.blankToDefault(reason, ""),
                LocalDateTime.now()
        );
        state.recentEdits().add(0, record);
        if (state.recentEdits().size() > MAX_RECENT_EDITS) {
            state.recentEdits().removeLast();
        }
        // 更新最近修改文件
        for (String filePath : record.changedFiles()) {
            state.recentFiles().removeIf(f -> f.filePath().equals(filePath));
            state.recentFiles().add(0, new RecentFile(filePath, LocalDateTime.now(), success));
            if (state.recentFiles().size() > MAX_RECENT_FILES) {
                state.recentFiles().removeLast();
            }
        }
        saveState(appId, state);
        log.debug("记录编辑结果，appId: {}, taskId: {}, success: {}", appId, taskId, success);
    }

    /**
     * 记录验证结果。
     *
     * @param appId  应用 ID
     * @param taskId 任务 ID
     * @param result 验证结果
     */
    public void recordValidationResult(Long appId, String taskId, BackgroundValidationService.ValidationResult result) {
        if (appId == null || result == null) {
            return;
        }
        EditState state = getOrCreateState(appId);
        ValidationRecord record = new ValidationRecord(
                taskId,
                result.status(),
                result.message(),
                LocalDateTime.now()
        );
        state.recentValidations().add(0, record);
        if (state.recentValidations().size() > MAX_RECENT_EDITS) {
            state.recentValidations().removeLast();
        }
        saveState(appId, state);
        log.debug("记录验证结果，appId: {}, taskId: {}, status: {}", appId, taskId, result.status());
    }

    /**
     * 获取最近修改的文件列表。
     * 连续改修时优先召回上次相关文件。
     *
     * @param appId 应用 ID
     * @param limit 最大数量
     * @return 最近修改的文件路径列表
     */
    public List<String> getRecentModifiedFiles(Long appId, int limit) {
        if (appId == null) {
            return List.of();
        }
        EditState state = getOrCreateState(appId);
        return state.recentFiles().stream()
                .filter(RecentFile::success)
                .map(RecentFile::filePath)
                .distinct()
                .limit(limit)
                .toList();
    }

    /**
     * 获取与用户消息相关的最近修改文件。
     * 基于用户消息关键词匹配。
     *
     * @param appId       应用 ID
     * @param userMessage 用户消息
     * @param limit       最大数量
     * @return 相关的文件路径列表
     */
    public List<String> getRelevantRecentFiles(Long appId, String userMessage, int limit) {
        if (appId == null || StrUtil.isBlank(userMessage)) {
            return List.of();
        }
        EditState state = getOrCreateState(appId);
        String normalizedMessage = userMessage.toLowerCase();
        return state.recentFiles().stream()
                .filter(RecentFile::success)
                .filter(file -> isRelevantToMessage(file.filePath(), normalizedMessage))
                .map(RecentFile::filePath)
                .distinct()
                .limit(limit)
                .toList();
    }

    /**
     * 获取最近编辑记录。
     *
     * @param appId 应用 ID
     * @param limit 最大数量
     * @return 最近编辑记录列表
     */
    public List<EditRecord> getRecentEdits(Long appId, int limit) {
        if (appId == null) {
            return List.of();
        }
        EditState state = getOrCreateState(appId);
        return state.recentEdits().stream()
                .limit(limit)
                .toList();
    }

    /**
     * 检查文件是否最近被修改过。
     *
     * @param appId      应用 ID
     * @param filePath   文件路径
     * @param withinHours 小时数
     * @return 是否最近被修改过
     */
    public boolean wasRecentlyModified(Long appId, String filePath, long withinHours) {
        if (appId == null || StrUtil.isBlank(filePath)) {
            return false;
        }
        EditState state = getOrCreateState(appId);
        return state.recentFiles().stream()
                .anyMatch(file -> file.filePath().equals(filePath) &&
                        file.lastModified().isAfter(LocalDateTime.now().minusHours(withinHours)));
    }

    /**
     * 获取编辑状态。
     */
    private EditState getOrCreateState(Long appId) {
        String cacheKey = String.valueOf(appId);
        return cache.computeIfAbsent(cacheKey, key -> loadState(appId));
    }

    /**
     * 加载编辑状态。
     */
    private EditState loadState(Long appId) {
        Path stateFile = resolveStateFile(appId);
        if (!Files.exists(stateFile)) {
            return new EditState(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            if (StrUtil.isBlank(json)) {
                return new EditState(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            }
            return JSONUtil.toBean(json, EditState.class);
        } catch (Exception e) {
            log.warn("加载编辑状态失败，appId: {}", appId, e);
            return new EditState(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * 保存编辑状态。
     */
    private void saveState(Long appId, EditState state) {
        String cacheKey = String.valueOf(appId);
        cache.put(cacheKey, state);
        Path stateFile = resolveStateFile(appId);
        try {
            Files.createDirectories(stateFile.getParent());
            String json = JSONUtil.toJsonPrettyStr(state);
            Files.writeString(stateFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("保存编辑状态失败，appId: {}", appId, e);
        }
    }

    /**
     * 解析状态文件路径。
     */
    private Path resolveStateFile(Long appId) {
        // 使用临时目录存储状态
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        return tempDir.resolve(STATE_DIRECTORY_NAME).resolve(appId + "-" + STATE_FILE_NAME);
    }

    /**
     * 检查文件是否与用户消息相关。
     */
    private boolean isRelevantToMessage(String filePath, String normalizedMessage) {
        String normalizedPath = filePath.toLowerCase();
        String fileName = normalizedPath.contains("/") ? normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1) : normalizedPath;
        // 移除扩展名
        String fileNameWithoutExt = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        // 检查文件名是否出现在消息中
        return normalizedMessage.contains(fileNameWithoutExt) || normalizedMessage.contains(normalizedPath);
    }

    /**
     * 编辑状态。
     */
    public record EditState(
            List<EditRecord> recentEdits,
            List<RecentFile> recentFiles,
            List<ValidationRecord> recentValidations
    ) {
        public EditState {
            recentEdits = recentEdits == null ? new ArrayList<>() : new ArrayList<>(recentEdits);
            recentFiles = recentFiles == null ? new ArrayList<>() : new ArrayList<>(recentFiles);
            recentValidations = recentValidations == null ? new ArrayList<>() : new ArrayList<>(recentValidations);
        }
    }

    /**
     * 编辑记录。
     */
    public record EditRecord(
            String taskId,
            String userMessage,
            List<String> changedFiles,
            boolean success,
            String reason,
            LocalDateTime timestamp
    ) {
    }

    /**
     * 最近修改的文件。
     */
    public record RecentFile(
            String filePath,
            LocalDateTime lastModified,
            boolean success
    ) {
    }

    /**
     * 验证记录。
     */
    public record ValidationRecord(
            String taskId,
            String status,
            String message,
            LocalDateTime timestamp
    ) {
    }
}
