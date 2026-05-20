package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立补丁执行器落盘结果契约。
 */
public record PatchApplyResult(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String projectPath,
        int plannedOperationCount,
        int appliedOperationCount,
        int rejectedOperationCount,
        List<String> appliedFiles,
        List<String> rejectedOperations,
        String reason,
        LocalDateTime createdAt
) {

    public PatchApplyResult {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_patch_executor");
        status = StrUtil.blankToDefault(status, "skipped");
        projectPath = StrUtil.blankToDefault(projectPath, "");
        appliedFiles = appliedFiles == null ? List.of() : List.copyOf(appliedFiles);
        rejectedOperations = rejectedOperations == null ? List.of() : List.copyOf(rejectedOperations);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public static PatchApplyResult applied(Long appId,
                                           String taskId,
                                           String projectPath,
                                           int plannedOperationCount,
                                           List<String> appliedFiles) {
        return new PatchApplyResult(
                "v1",
                "local_patch_executor",
                "applied",
                appId,
                taskId,
                projectPath,
                plannedOperationCount,
                appliedFiles.size(),
                0,
                appliedFiles,
                List.of(),
                "",
                LocalDateTime.now()
        );
    }

    public static PatchApplyResult rejected(Long appId,
                                            String taskId,
                                            String projectPath,
                                            int plannedOperationCount,
                                            List<String> rejectedOperations,
                                            String reason) {
        return new PatchApplyResult(
                "v1",
                "local_patch_executor",
                "rejected",
                appId,
                taskId,
                projectPath,
                plannedOperationCount,
                0,
                rejectedOperations == null ? 0 : rejectedOperations.size(),
                List.of(),
                rejectedOperations,
                reason,
                LocalDateTime.now()
        );
    }

    public static PatchApplyResult skipped(Long appId, String taskId, String projectPath, String reason) {
        return new PatchApplyResult(
                "v1",
                "local_patch_executor",
                "skipped",
                appId,
                taskId,
                projectPath,
                0,
                0,
                0,
                List.of(),
                List.of(),
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
        payload.put("projectPath", projectPath);
        payload.put("plannedOperationCount", plannedOperationCount);
        payload.put("appliedOperationCount", appliedOperationCount);
        payload.put("rejectedOperationCount", rejectedOperationCount);
        payload.put("appliedFiles", appliedFiles);
        payload.put("rejectedOperations", rejectedOperations);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }
}
