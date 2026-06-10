package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将计划内文件范围与生成后的真实 diff 对齐，产出 patch 应用结果。
 */
@Component
public class GenerationPatchResultService {

    public PatchResult evaluate(Long appId,
                                String taskId,
                                GenerationArtifact changePlanArtifact,
                                GenerationArtifact diffSummaryArtifact) {
        ChangePlan changePlan = ChangePlan.fromPayload(payload(changePlanArtifact));
        if (changePlan == null) {
            return PatchResult.skipped(appId, taskId, "change_plan_missing");
        }
        if ("project_bootstrap".equals(changePlan.changeScope())) {
            return PatchResult.skipped(appId, taskId, "project_bootstrap_not_patch_first");
        }
        Map<String, Object> diffPayload = payload(diffSummaryArtifact);
        if (diffPayload.isEmpty()) {
            return PatchResult.skipped(appId, taskId, "diff_summary_missing");
        }
        if (!"created".equals(String.valueOf(diffPayload.get("status")))) {
            return PatchResult.skipped(appId, taskId, "diff_summary_not_created");
        }
        List<String> actualAddedFiles = normalizeFiles(diffPayload.get("addedFiles"));
        List<String> actualModifiedFiles = normalizeFiles(diffPayload.get("modifiedFiles"));
        List<String> actualDeletedFiles = normalizeFiles(diffPayload.get("deletedFiles"));
        List<String> unplannedFiles = new ArrayList<>();
        unplannedFiles.addAll(outsidePlan("add", actualAddedFiles, changePlan.addFiles()));
        unplannedFiles.addAll(outsidePlan("modify", actualModifiedFiles, changePlan.modifyFiles()));
        unplannedFiles.addAll(outsidePlan("delete", actualDeletedFiles, changePlan.deleteFiles()));
        List<String> missingPlannedFiles = new ArrayList<>();
        missingPlannedFiles.addAll(missingFromActual("add", changePlan.addFiles(), actualAddedFiles));
        missingPlannedFiles.addAll(missingFromActual("modify", changePlan.modifyFiles(), actualModifiedFiles));
        missingPlannedFiles.addAll(missingFromActual("delete", changePlan.deleteFiles(), actualDeletedFiles));
        return PatchResult.created(
                appId,
                taskId,
                changePlan,
                actualAddedFiles,
                actualModifiedFiles,
                actualDeletedFiles,
                unplannedFiles,
                missingPlannedFiles
        );
    }

    public String renderText(PatchResult result) {
        if (result == null) {
            return "Patch 应用结果不可用";
        }
        if ("applied".equals(result.status())) {
            return "Patch 应用结果已对齐 ChangePlan。";
        }
        if ("drifted".equals(result.status())) {
            return "Patch 应用结果偏离 ChangePlan：计划外 "
                    + result.unplannedFileCount()
                    + " 个，计划内未落盘 "
                    + result.missingPlannedFileCount()
                    + " 个。";
        }
        return "Patch 应用结果已跳过: " + result.reason();
    }

    private List<String> outsidePlan(String kind, List<String> actualFiles, List<String> plannedFiles) {
        Set<String> plannedSet = new LinkedHashSet<>(plannedFiles);
        return actualFiles.stream()
                .filter(file -> !plannedSet.contains(file))
                .map(file -> kind + ":" + file)
                .toList();
    }

    private List<String> missingFromActual(String kind, List<String> plannedFiles, List<String> actualFiles) {
        Set<String> actualSet = new LinkedHashSet<>(actualFiles);
        return plannedFiles.stream()
                .filter(file -> !actualSet.contains(file))
                .map(file -> kind + ":" + file)
                .toList();
    }

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private List<String> normalizeFiles(Object value) {
        if (value instanceof Collection<?> collection) {
            return ChangePlan.normalizeFilePaths(collection.stream()
                    .filter(item -> item != null && StrUtil.isNotBlank(String.valueOf(item)))
                    .map(String::valueOf)
                    .toList());
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return ChangePlan.normalizeFilePaths(List.of(text));
        }
        return List.of();
    }
}
