package com.yupi.yuaicodemother.orchestration.artifact;

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
