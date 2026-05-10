package com.yupi.yuaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成后差异摘要契约。
 */
public record DiffSummary(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String basePath,
        String currentPath,
        int addedCount,
        int modifiedCount,
        int deletedCount,
        List<String> addedFiles,
        List<String> modifiedFiles,
        List<String> deletedFiles,
        List<String> modifiedDetails,
        String reason,
        LocalDateTime createdAt
) {

    public DiffSummary {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_snapshot");
        status = StrUtil.blankToDefault(status, "skipped");
        addedFiles = addedFiles == null ? List.of() : List.copyOf(addedFiles);
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        deletedFiles = deletedFiles == null ? List.of() : List.copyOf(deletedFiles);
        modifiedDetails = modifiedDetails == null ? List.of() : List.copyOf(modifiedDetails);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public static DiffSummary created(Long appId,
                                      String taskId,
                                      String basePath,
                                      String currentPath,
                                      List<String> addedFiles,
                                      List<String> modifiedFiles,
                                      List<String> deletedFiles,
                                      List<String> modifiedDetails) {
        return new DiffSummary(
                "v1",
                "local_snapshot",
                "created",
                appId,
                taskId,
                basePath,
                currentPath,
                addedFiles == null ? 0 : addedFiles.size(),
                modifiedFiles == null ? 0 : modifiedFiles.size(),
                deletedFiles == null ? 0 : deletedFiles.size(),
                addedFiles,
                modifiedFiles,
                deletedFiles,
                modifiedDetails,
                "",
                LocalDateTime.now()
        );
    }

    public static DiffSummary skipped(Long appId,
                                      String taskId,
                                      String basePath,
                                      String currentPath,
                                      String reason) {
        return new DiffSummary(
                "v1",
                "local_snapshot",
                "skipped",
                appId,
                taskId,
                basePath,
                currentPath,
                0,
                0,
                0,
                List.of(),
                List.of(),
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
        payload.put("basePath", basePath);
        payload.put("currentPath", currentPath);
        payload.put("addedCount", addedCount);
        payload.put("modifiedCount", modifiedCount);
        payload.put("deletedCount", deletedCount);
        payload.put("addedFiles", addedFiles);
        payload.put("modifiedFiles", modifiedFiles);
        payload.put("deletedFiles", deletedFiles);
        payload.put("modifiedDetails", modifiedDetails);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }
}
