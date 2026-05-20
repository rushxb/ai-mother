package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Patch-first 实际落盘结果契约。
 */
public record PatchResult(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        List<String> plannedAddFiles,
        List<String> plannedModifyFiles,
        List<String> plannedDeleteFiles,
        List<String> actualAddedFiles,
        List<String> actualModifiedFiles,
        List<String> actualDeletedFiles,
        List<String> unplannedFiles,
        List<String> missingPlannedFiles,
        int matchedFileCount,
        int unplannedFileCount,
        int missingPlannedFileCount,
        String reason,
        LocalDateTime createdAt
) {

    public PatchResult {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_diff");
        status = StrUtil.blankToDefault(status, "skipped");
        plannedAddFiles = plannedAddFiles == null ? List.of() : List.copyOf(plannedAddFiles);
        plannedModifyFiles = plannedModifyFiles == null ? List.of() : List.copyOf(plannedModifyFiles);
        plannedDeleteFiles = plannedDeleteFiles == null ? List.of() : List.copyOf(plannedDeleteFiles);
        actualAddedFiles = actualAddedFiles == null ? List.of() : List.copyOf(actualAddedFiles);
        actualModifiedFiles = actualModifiedFiles == null ? List.of() : List.copyOf(actualModifiedFiles);
        actualDeletedFiles = actualDeletedFiles == null ? List.of() : List.copyOf(actualDeletedFiles);
        unplannedFiles = unplannedFiles == null ? List.of() : List.copyOf(unplannedFiles);
        missingPlannedFiles = missingPlannedFiles == null ? List.of() : List.copyOf(missingPlannedFiles);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public static PatchResult created(Long appId,
                                      String taskId,
                                      ChangePlan changePlan,
                                      List<String> actualAddedFiles,
                                      List<String> actualModifiedFiles,
                                      List<String> actualDeletedFiles,
                                      List<String> unplannedFiles,
                                      List<String> missingPlannedFiles) {
        return new PatchResult(
                "v1",
                "local_diff",
                unplannedFiles.isEmpty() && missingPlannedFiles.isEmpty() ? "applied" : "drifted",
                appId,
                taskId,
                changePlan.addFiles(),
                changePlan.modifyFiles(),
                changePlan.deleteFiles(),
                actualAddedFiles,
                actualModifiedFiles,
                actualDeletedFiles,
                unplannedFiles,
                missingPlannedFiles,
                actualAddedFiles.size() + actualModifiedFiles.size() + actualDeletedFiles.size() - unplannedFiles.size(),
                unplannedFiles.size(),
                missingPlannedFiles.size(),
                unplannedFiles.isEmpty() && missingPlannedFiles.isEmpty() ? "" : "actual_diff_outside_change_plan",
                LocalDateTime.now()
        );
    }

    public static PatchResult skipped(Long appId, String taskId, String reason) {
        return new PatchResult(
                "v1",
                "local_diff",
                "skipped",
                appId,
                taskId,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
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
        payload.put("plannedAddFiles", plannedAddFiles);
        payload.put("plannedModifyFiles", plannedModifyFiles);
        payload.put("plannedDeleteFiles", plannedDeleteFiles);
        payload.put("actualAddedFiles", actualAddedFiles);
        payload.put("actualModifiedFiles", actualModifiedFiles);
        payload.put("actualDeletedFiles", actualDeletedFiles);
        payload.put("unplannedFiles", unplannedFiles);
        payload.put("missingPlannedFiles", missingPlannedFiles);
        payload.put("matchedFileCount", matchedFileCount);
        payload.put("unplannedFileCount", unplannedFileCount);
        payload.put("missingPlannedFileCount", missingPlannedFileCount);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }
}
