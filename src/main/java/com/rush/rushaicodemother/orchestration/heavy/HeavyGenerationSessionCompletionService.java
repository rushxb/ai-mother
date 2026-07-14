package com.rush.rushaicodemother.orchestration.heavy;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HeavyGenerationSessionCompletionService {

    private final GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;
    private final GenerationTaskLifecycleService generationTaskLifecycleService;

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
        recordUserWaitMetric(session, preparation, status);
        generationTaskLifecycleService.completeGenerationAndCharge(
                preparation.taskId(),
                appId,
                outcome.taskStatus(),
                null,
                buildMemorySummary(preparation, status)
        );
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

    private void recordUserWaitMetric(GenerationSession session,
                                      GenerationPreparation preparation,
                                      String status) {
        if (session == null || preparation == null) {
            return;
        }
        long orchestrationDurationMs = preparation.timings() == null
                ? 0L
                : preparation.timings().values().stream().mapToLong(Long::longValue).sum();
        generationOrchestrationMetricsCollector.recordUserWaitDuration(
                orchestrationMode(preparation),
                preparation.targetType() == null ? "unknown" : preparation.targetType().getValue(),
                status,
                Duration.between(session.startedAt(), Instant.now()).plusMillis(Math.max(0L, orchestrationDurationMs))
        );
    }
}
