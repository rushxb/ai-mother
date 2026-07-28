package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成前标准回滚点契约。
 */
public record RollbackPoint(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String snapshotName,
        String snapshotPath,
        String projectPath,
        String sourceType,
        String targetType,
        int fileCount,
        String reason,
        LocalDateTime createdAt
) {

    public RollbackPoint {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_snapshot");
        status = StrUtil.blankToDefault(status, "skipped");
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /**
 * 创建{@code d}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param snapshotName 快照名称
 * @param snapshotPath 快照路径
 * @param projectPath 项目路径
 * @param sourceType 来源类型
 * @param targetType 目标类型
 * @param fileCount 文件数量
 * @return {@code d}
 */
    public static RollbackPoint created(Long appId,
                                        String taskId,
                                        String snapshotName,
                                        String snapshotPath,
                                        String projectPath,
                                        String sourceType,
                                        String targetType,
                                        int fileCount) {
        return new RollbackPoint(
                "v1",
                "local_snapshot",
                "created",
                appId,
                taskId,
                snapshotName,
                snapshotPath,
                projectPath,
                sourceType,
                targetType,
                fileCount,
                "",
                LocalDateTime.now()
        );
    }

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectPath 项目路径
 * @param sourceType 来源类型
 * @param targetType 目标类型
 * @param reason 原因
 * @return 回滚点
 */
    public static RollbackPoint skipped(Long appId,
                                        String taskId,
                                        String projectPath,
                                        String sourceType,
                                        String targetType,
                                        String reason) {
        return new RollbackPoint(
                "v1",
                "local_snapshot",
                "skipped",
                appId,
                taskId,
                "",
                "",
                projectPath,
                sourceType,
                targetType,
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("snapshotName", snapshotName);
        payload.put("snapshotPath", snapshotPath);
        payload.put("projectPath", projectPath);
        payload.put("sourceType", sourceType);
        payload.put("targetType", targetType);
        payload.put("fileCount", fileCount);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }

    public boolean created() {
        return "created".equals(status);
    }
}
