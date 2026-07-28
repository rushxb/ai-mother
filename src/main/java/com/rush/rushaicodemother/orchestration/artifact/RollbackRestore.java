package com.rush.rushaicodemother.orchestration.artifact;

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

    /** 创建回滚恢复实例并完成必要的依赖和初始状态设置。 */
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

    /**
 * 返回{@code restored}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param rollbackStrategy {@code rollbackStrategy} 对应的调用参数
 * @param snapshotPath 快照路径
 * @param projectPath 项目路径
 * @param backupPath {@code backupPath} 对应的调用参数
 * @param restoredFileCount {@code restoredFileCount} 对应的调用参数
 * @return 回滚恢复
 */
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

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param rollbackStrategy {@code rollbackStrategy} 对应的调用参数
 * @param snapshotPath 快照路径
 * @param projectPath 项目路径
 * @param reason 原因
 * @return 回滚恢复
 */
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

    /**
 * 将{@code ed}标记为失败并记录原因。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param rollbackStrategy {@code rollbackStrategy} 对应的调用参数
 * @param snapshotPath 快照路径
 * @param projectPath 项目路径
 * @param backupPath {@code backupPath} 对应的调用参数
 * @param reason 原因
 * @return {@code ed}
 */
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
