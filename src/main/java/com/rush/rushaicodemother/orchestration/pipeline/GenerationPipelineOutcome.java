package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;

/** 生成管线返回给统一任务执行器的结果。 */
public record GenerationPipelineOutcome(
        GenerationPipelineDisposition disposition,
        String route,
        GenerationTaskStatus terminalStatus,
        String reason,
        String resultSummary,
        GenerationCompletionEvidenceSet completionEvidence,
        Integer changedFileCount,
        Integer repairRounds
) {

    /** 创建生成流水线结果实例并完成必要的依赖和初始状态设置。 */
    public GenerationPipelineOutcome {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (disposition == null) {
            throw new IllegalArgumentException("生成管线结果类型不能为空");
        }
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("生成管线路由不能为空");
        }
        route = route.trim();
        reason = normalize(reason);
        resultSummary = normalize(resultSummary);
        completionEvidence = completionEvidence == null
                ? GenerationCompletionEvidenceSet.empty()
                : completionEvidence;
        // 负值视为未采集而不是异常：这两项只用于 L3 归因，不参与完成判定。
        if (changedFileCount != null && changedFileCount < 0) {
            changedFileCount = null;
        }
        if (repairRounds != null && repairRounds < 0) {
            repairRounds = null;
        }
        if (disposition == GenerationPipelineDisposition.COMPLETED
                && (terminalStatus == null || !terminalStatus.isTerminal())) {
            throw new IllegalArgumentException("已完成的生成管线必须提供终态");
        }
        if (disposition != GenerationPipelineDisposition.COMPLETED && terminalStatus != null) {
            throw new IllegalArgumentException("未完成的生成管线不能提供终态");
        }
        if (disposition == GenerationPipelineDisposition.COMPLETED && resultSummary == null) {
            throw new IllegalArgumentException("已完成的生成管线必须提供结果摘要");
        }
        if (disposition == GenerationPipelineDisposition.COMPLETED
                && terminalStatus != GenerationTaskStatus.SUCCESS
                && reason == null) {
            throw new IllegalArgumentException("非成功终态必须提供终态原因");
        }
        if (disposition != GenerationPipelineDisposition.COMPLETED && resultSummary != null) {
            throw new IllegalArgumentException("未完成的生成管线不能提供结果摘要");
        }
        if (disposition == GenerationPipelineDisposition.FALLBACK && reason == null) {
            throw new IllegalArgumentException("回退结果必须提供回退原因");
        }
    }


    /** 兼容既有非成功结果和测试构造；成功结果应使用携带证据的工厂方法。 */
    public GenerationPipelineOutcome(GenerationPipelineDisposition disposition,
                                     String route,
                                     GenerationTaskStatus terminalStatus,
                                     String reason,
                                     String resultSummary) {
        this(disposition, route, terminalStatus, reason, resultSummary,
                GenerationCompletionEvidenceSet.empty());
    }

    /** 兼容既有调用：不携带 L3 归因指标。 */
    public GenerationPipelineOutcome(GenerationPipelineDisposition disposition,
                                     String route,
                                     GenerationTaskStatus terminalStatus,
                                     String reason,
                                     String resultSummary,
                                     GenerationCompletionEvidenceSet completionEvidence) {
        this(disposition, route, terminalStatus, reason, resultSummary, completionEvidence, null, null);
    }

    public static GenerationPipelineOutcome completed(String route,
                                                       GenerationTaskStatus status,
                                                       String reason,
                                                       String resultSummary) {
        return completed(route, status, reason, resultSummary, GenerationCompletionEvidenceSet.empty());
    }

    public static GenerationPipelineOutcome completed(String route,
                                                       GenerationTaskStatus status,
                                                       String reason,
                                                       String resultSummary,
                                                       GenerationCompletionEvidenceSet completionEvidence) {
        return new GenerationPipelineOutcome(
                GenerationPipelineDisposition.COMPLETED, route, status, reason, resultSummary, completionEvidence);
    }

    /**
     * 创建携带 L3 归因指标的终态结果。
     *
     * @param route 路由
     * @param status 终态
     * @param reason 终态原因
     * @param resultSummary 结果摘要
     * @param completionEvidence 完成证据
     * @param changedFileCount 有效变更文件数，未采集传 {@code null}
     * @param repairRounds 修复轮次，未采集传 {@code null}
     * @return 生成流水线结果
     */
    public static GenerationPipelineOutcome completed(String route,
                                                       GenerationTaskStatus status,
                                                       String reason,
                                                       String resultSummary,
                                                       GenerationCompletionEvidenceSet completionEvidence,
                                                       Integer changedFileCount,
                                                       Integer repairRounds) {
        return new GenerationPipelineOutcome(
                GenerationPipelineDisposition.COMPLETED, route, status, reason, resultSummary,
                completionEvidence, changedFileCount, repairRounds);
    }

    public static GenerationPipelineOutcome running(String route) {
        return new GenerationPipelineOutcome(
                GenerationPipelineDisposition.RUNNING, route, null, null, null,
                GenerationCompletionEvidenceSet.empty());
    }

    public static GenerationPipelineOutcome fallback(String route, String reason) {
        return new GenerationPipelineOutcome(
                GenerationPipelineDisposition.FALLBACK, route, null, reason, null,
                GenerationCompletionEvidenceSet.empty());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
