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
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryRequest;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HeavyGenerationSessionCompletionService {

    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;
    private final GenerationOutcomeMemoryService outcomeMemoryService;

    /**
     * 在调用者自动声明会话完成后保留终端生命周期数据。
     * 流完成和基础设施清理仍然是协调器的责任。
     */
    public void completeClaimed(Long appId,
                                GenerationSession session,
                                GenerationPreparation preparation,
                                GenerationTerminalOutcome outcome) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (session == null || preparation == null) {
            return;
        }
        if (outcome == null) {
            throw new IllegalArgumentException("generation terminal outcome must not be null");
        }
        String status = outcome.status();
        String memorySummary = buildMemorySummary(preparation, status);
        RuntimeException lifecycleFailure = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (outcome == GenerationTerminalOutcome.SUCCESS) {
                generationTaskLifecycleService.completeGenerationAndCharge(
                        preparation.taskId(),
                        appId,
                        outcome.taskStatus(),
                        null,
                        memorySummary
                );
            } else {
                generationTaskLifecycleService.completeGeneration(
                        preparation.taskId(),
                        appId,
                        outcome.taskStatus(),
                        outcome.status(),
                        memorySummary
                );
            }
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

    /**
 * 返回编排模式。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @return 处理后的重型生成会话完成文本
 */
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

    /** 构建并返回记忆汇总。 */
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

    /** 处理记录结果。 */
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
        outcomeMemoryService.remember(new GenerationOutcomeMemoryRequest(
                preparation.taskId(),
                session.taskRequest().app().getTenantId(),
                appId,
                session.taskRequest().loginUser().getId(),
                outcome.taskStatus(),
                session.taskRequest().message(),
                memorySummary,
                orchestrationMode(preparation),
                preparation.targetType() == null ? "unknown" : preparation.targetType().getValue()
        ));
    }

}
