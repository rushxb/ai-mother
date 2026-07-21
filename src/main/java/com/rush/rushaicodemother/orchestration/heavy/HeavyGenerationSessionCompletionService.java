package com.rush.rushaicodemother.orchestration.heavy;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.memory.GenerationSemanticMemoryService;
import com.rush.rushaicodemother.memory.MemoryType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HeavyGenerationSessionCompletionService {

    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;
    private final GenerationSemanticMemoryService semanticMemoryService;

    /**
     * Persists terminal lifecycle data after the caller has atomically claimed session completion.
     * Stream completion and infrastructure cleanup remain the orchestrator's responsibility.
     */
    public void completeClaimed(Long appId,
                                GenerationSession session,
                                GenerationPreparation preparation,
                                GenerationTerminalOutcome outcome) {
        if (session == null || preparation == null) {
            return;
        }
        if (outcome == null) {
            throw new IllegalArgumentException("generation terminal outcome must not be null");
        }
        String status = outcome.status();
        String memorySummary = buildMemorySummary(preparation, status);
        RuntimeException lifecycleFailure = null;
        try {
            generationTaskLifecycleService.completeGenerationAndCharge(
                    preparation.taskId(),
                    appId,
                    outcome.taskStatus(),
                    null,
                    memorySummary
            );
        } catch (RuntimeException failure) {
            lifecycleFailure = failure;
            throw failure;
        } finally {
            try {
                String reason = outcome == GenerationTerminalOutcome.SUCCESS ? null : outcome.status();
                if (session.executionContext() != null
                        && session.executionContext().executionFence() != null) {
                    generationTaskRuntimeLifecycleService.completeOwned(
                            session.executionContext().executionFence(), outcome.taskStatus(), reason);
                } else {
                    generationTaskRuntimeLifecycleService.completeUnowned(
                            preparation.taskId(), outcome.taskStatus(), reason);
                }
            } catch (RuntimeException runtimeFailure) {
                if (lifecycleFailure != null) {
                    lifecycleFailure.addSuppressed(runtimeFailure);
                } else {
                    throw runtimeFailure;
                }
            }
        }
        rememberOutcome(appId, session, preparation, outcome, memorySummary);
    }

    public String orchestrationMode(GenerationPreparation preparation) {
        if (preparation == null || preparation.events() == null) {
            return "unknown";
        }
        return preparation.events().stream()
                .map(GenerationStreamEvent::getData)
                .filter(map -> map != null && map.get("orchestrationMode") != null)
                .map(map -> String.valueOf(map.get("orchestrationMode")))
                .findFirst()
                .orElse("unknown");
    }

    private String buildMemorySummary(GenerationPreparation preparation, String status) {
        if (preparation == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("任务状态：" + StrUtil.blankToDefault(status, "unknown"));
        lines.add("生成类型：" + (preparation.targetType() == null ? "unknown" : preparation.targetType().getValue())
                + "，阶段：" + StrUtil.blankToDefault(preparation.generatingStage(), "unknown")
                + "，构建校验：" + preparation.requiresBuildValidation());
        GenerationArtifact changePlan = preparation.artifact("change_plan");
        if (changePlan != null) {
            lines.add("变更计划：" + compactMemoryText(String.valueOf(changePlan.payload()), 900));
        }
        GenerationArtifact diffSummary = preparation.artifact("diff_summary");
        if (diffSummary != null) {
            lines.add("实际变更：" + compactMemoryText(String.valueOf(diffSummary.payload()), 900));
        }
        GenerationArtifact patchResult = preparation.artifact("patch_result");
        if (patchResult != null) {
            lines.add("Patch 结果：" + compactMemoryText(String.valueOf(patchResult.payload()), 700));
        }
        if (preparation.qualityGateResult() != null) {
            lines.add("质量门禁：passed=" + preparation.qualityGateResult().passed()
                    + ", blockers=" + compactMemoryText(String.valueOf(preparation.qualityGateResult().blockers()), 500));
        }
        return compactMemoryText(String.join("\n", lines), 5000);
    }

    private String compactMemoryText(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private void rememberOutcome(Long appId,
                                 GenerationSession session,
                                 GenerationPreparation preparation,
                                 GenerationTerminalOutcome outcome,
                                 String memorySummary) {
        if (session.taskRequest() == null || session.taskRequest().loginUser() == null) {
            return;
        }
        if (session.taskRequest().app() == null
                || session.taskRequest().app().getTenantId() == null) {
            return;
        }
        String userPrompt = session.taskRequest().message();
        String content = "User request: " + StrUtil.blankToDefault(userPrompt, "")
                + "\nOutcome: " + memorySummary;
        semanticMemoryService.rememberAsync(
                session.taskRequest().app().getTenantId(),
                appId,
                session.taskRequest().loginUser().getId(),
                preparation.taskId(),
                outcome == GenerationTerminalOutcome.SUCCESS
                        ? MemoryType.TASK_OUTCOME
                        : MemoryType.FAILURE_LESSON,
                content,
                Map.of(
                        "status", outcome.status(),
                        "orchestrationMode", orchestrationMode(preparation),
                        "targetType", preparation.targetType() == null
                                ? "unknown"
                                : preparation.targetType().getValue()
                )
        );
    }

}
