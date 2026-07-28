package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
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

    /**
 * 根据当前上下文解析{@code Or}创建。
 *
 * @param snapshotName 快照名称
 * @param automaticPrefix {@code automaticPrefix} 对应的调用参数
 * @return 处理后的{@code Or}创建文本
 */
    public String resolveOrCreate(String snapshotName, String automaticPrefix) {
        if (StrUtil.isBlank(snapshotName)) {
            return createAutomaticName(automaticPrefix);
        }
        return validateRequired(snapshotName);
    }

    /**
 * 校验{@code ate}{@code Required}是否有效。
 *
 * @param snapshotName 快照名称
 * @return 处理后的{@code ate}{@code Required}文本
 */
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

    /**
 * 创建{@code Automatic}名称。
 *
 * @param prefix {@code prefix} 对应的调用参数
 * @return 处理后的{@code Automatic}名称文本
 */
    public String createAutomaticName(String prefix) {
        String normalizedPrefix = validateRequired(prefix);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);
        return validateRequired(normalizedPrefix + "_" + timestamp + "_" + randomSuffix);
    }

    /** 为编排工件构建有界的自动名称，而不暴露任意任务 ID 路径数据。 */
    public String createTaskScopedName(String prefix, String taskId) {
        String normalizedTaskId = normalizeTaskSegment(taskId, MAX_TASK_SEGMENT_LENGTH);
        return createAutomaticName(validateRequired(prefix) + "_" + normalizedTaskId);
    }

    /** 为可恢复副作用生成稳定且抗碰撞的任务级快照名称。 */
    public String createStableTaskScopedName(String prefix, String taskId) {
        String normalizedPrefix = validateRequired(prefix);
        String sourceTaskId = StrUtil.blankToDefault(taskId, "unknown");
        String digest = DigestUtil.sha256Hex(sourceTaskId).substring(0, 12);
        int availableTaskLength = MAX_NAME_LENGTH - normalizedPrefix.length() - digest.length() - 2;
        if (availableTaskLength <= 0) {
            throw new ValidationException("快照名称前缀过长，无法附加任务标识");
        }
        String normalizedTaskId = normalizeTaskSegment(
                sourceTaskId, Math.min(MAX_TASK_SEGMENT_LENGTH, availableTaskLength));
        return validateRequired(normalizedPrefix + "_" + normalizedTaskId + "_" + digest);
    }

    private String normalizeTaskSegment(String taskId, int maxLength) {
        String normalizedTaskId = StrUtil.blankToDefault(taskId, "unknown")
                .replaceAll("[^A-Za-z0-9_-]", "_");
        return normalizedTaskId.length() <= maxLength
                ? normalizedTaskId
                : normalizedTaskId.substring(0, maxLength);
    }

    public static final class ValidationException extends IllegalArgumentException {

        public ValidationException(String message) {
            super(message);
        }
    }
}
