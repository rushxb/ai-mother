package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 快照目录名称的统一策略。
 *
 * <p>快照名称只允许单个安全路径段，所有手工快照和自动快照均通过同一规则生成或校验，
 * 避免工具之间出现不一致的路径约束。</p>
 */
@Component
public class SnapshotNamePolicy {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_TASK_SEGMENT_LENGTH = 48;
    private static final Pattern SAFE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    public String resolveOrCreate(String snapshotName, String automaticPrefix) {
        if (StrUtil.isBlank(snapshotName)) {
            return createAutomaticName(automaticPrefix);
        }
        return validateRequired(snapshotName);
    }

    public String validateRequired(String snapshotName) {
        if (StrUtil.isBlank(snapshotName)) {
            throw new ValidationException("快照名称不能为空");
        }
        String normalized = snapshotName.trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("快照名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        if (!SAFE_NAME_PATTERN.matcher(normalized).matches()) {
            throw new ValidationException("快照名称只能包含字母、数字、下划线和短横线");
        }
        return normalized;
    }

    public String createAutomaticName(String prefix) {
        String normalizedPrefix = validateRequired(prefix);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);
        return validateRequired(normalizedPrefix + "_" + timestamp + "_" + randomSuffix);
    }

    /** Builds a bounded automatic name for orchestration artifacts without exposing arbitrary task-id path data. */
    public String createTaskScopedName(String prefix, String taskId) {
        String normalizedTaskId = StrUtil.blankToDefault(taskId, "unknown")
                .replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalizedTaskId.length() > MAX_TASK_SEGMENT_LENGTH) {
            normalizedTaskId = normalizedTaskId.substring(0, MAX_TASK_SEGMENT_LENGTH);
        }
        return createAutomaticName(validateRequired(prefix) + "_" + normalizedTaskId);
    }

    public static final class ValidationException extends IllegalArgumentException {

        public ValidationException(String message) {
            super(message);
        }
    }
}
