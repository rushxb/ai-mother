package com.yupi.yuaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 失败后本地快照恢复结果契约。
 */
public record RollbackRestore(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String rollbackStrategy,
        String snapshotPath,
        String projectPath,
        String backupPath,
        int restoredFileCount,
        String reason,
        LocalDateTime restoredAt
) {

    public RollbackRestore {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_snapshot");
        status = StrUtil.blankToDefault(status, "skipped");
        rollbackStrategy = StrUtil.blankToDefault(rollbackStrategy, "manual_retry_without_snapshot");
        snapshotPath = StrUtil.blankToDefault(snapshotPath, "");
        projectPath = StrUtil.blankToDefault(projectPath, "");
        backupPath = StrUtil.blankToDefault(backupPath, "");
        reason = StrUtil.blankToDefault(reason, "");
        restoredAt = restoredAt == null ? LocalDateTime.now() : restoredAt;
    }

    public static RollbackRestore restored(Long appId,
                                           String taskId,
                                           String rollbackStrategy,
                                           String snapshotPath,
                                           String projectPath,
                                           String backupPath,
                                           int restoredFileCount) {
        return new RollbackRestore(
                "v1",
                "local_snapshot",
                "restored",
                appId,
                taskId,
                rollbackStrategy,
                snapshotPath,
                projectPath,
                backupPath,
                restoredFileCount,
                "",
                LocalDateTime.now()
        );
    }

    public static RollbackRestore skipped(Long appId,
                                          String taskId,
                                          String rollbackStrategy,
                                          String snapshotPath,
                                          String projectPath,
                                          String reason) {
        return new RollbackRestore(
                "v1",
                "local_snapshot",
                "skipped",
                appId,
                taskId,
                rollbackStrategy,
                snapshotPath,
                projectPath,
                "",
                0,
                reason,
                LocalDateTime.now()
        );
    }

    public static RollbackRestore failed(Long appId,
                                         String taskId,
                                         String rollbackStrategy,
                                         String snapshotPath,
                                         String projectPath,
                                         String backupPath,
                                         String reason) {
        return new RollbackRestore(
                "v1",
                "local_snapshot",
                "failed",
                appId,
                taskId,
                rollbackStrategy,
                snapshotPath,
                projectPath,
                backupPath,
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
        payload.put("rollbackStrategy", rollbackStrategy);
        payload.put("snapshotPath", snapshotPath);
        payload.put("projectPath", projectPath);
        payload.put("backupPath", backupPath);
        payload.put("restoredFileCount", restoredFileCount);
        payload.put("reason", reason);
        payload.put("restoredAt", restoredAt.toString());
        return payload;
    }
}
