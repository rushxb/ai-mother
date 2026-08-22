package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将计划内文件范围与生成后的真实 diff 对齐，产出 patch 应用结果。
 */
@Component
public class GenerationPatchResultService {

    /**
 * 返回{@code evaluate}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param changePlanArtifact {@code changePlanArtifact} 对应的调用参数
 * @param diffSummaryArtifact {@code diffSummaryArtifact} 对应的调用参数
 * @return 生成补丁结果
 */
    public PatchResult evaluate(Long appId,
                                String taskId,
                                GenerationArtifact changePlanArtifact,
                                GenerationArtifact diffSummaryArtifact) {
        if (changePlanArtifact == null) {
            return PatchResult.skipped(appId, taskId, "change_plan_missing");
        }
        ChangePlan changePlan;
        try {
            changePlan = ChangePlan.fromArtifact(changePlanArtifact);
        } catch (IllegalArgumentException invalidArtifact) {
            return PatchResult.skipped(appId, taskId, "change_plan_invalid");
        }
        if ("project_bootstrap".equals(changePlan.changeScope())) {
            return PatchResult.skipped(appId, taskId, "project_bootstrap_not_patch_first");
        }
        if (diffSummaryArtifact == null) {
            return PatchResult.skipped(appId, taskId, "diff_summary_missing");
        }
        DiffSummary diffSummary;
        try {
            diffSummary = DiffSummary.fromArtifact(diffSummaryArtifact, appId, taskId);
        } catch (IllegalArgumentException | NullPointerException invalidArtifact) {
            // Patch 结果属于用户可见的落盘事实，损坏或串任务的差异制品必须失败关闭。
            return PatchResult.skipped(appId, taskId, "diff_summary_invalid");
        }
        if (!diffSummary.created()) {
            return PatchResult.skipped(appId, taskId, "diff_summary_not_created");
        }
        List<String> actualAddedFiles = normalizeFiles(diffSummary.addedFiles());
        List<String> actualModifiedFiles = normalizeFiles(diffSummary.modifiedFiles());
        List<String> actualDeletedFiles = normalizeFiles(diffSummary.deletedFiles());
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

    /**
 * 渲染{@code Text}。
 *
 * @param result 待处理结果
 * @return 处理后的{@code Text}文本
 */
    public String renderText(PatchResult result) {
        if (result == null) {
            return "Patch 应用结果不可用";
        }
        if (result.applied()) {
            return "Patch 应用结果已对齐 ChangePlan。";
        }
        if (result.drifted()) {
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

    /** 规范化文件。 */
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
