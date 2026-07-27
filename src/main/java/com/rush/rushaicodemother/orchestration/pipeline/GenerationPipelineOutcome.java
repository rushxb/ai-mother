package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

/** 生成管线返回给统一任务执行器的结果。 */
public record GenerationPipelineOutcome(
        GenerationPipelineDisposition disposition,
        String route,
        GenerationTaskStatus terminalStatus,
        String reason,
        String resultSummary
) {

    public GenerationPipelineOutcome {
        if (disposition == null) {
            throw new IllegalArgumentException("生成管线结果类型不能为空");
        }
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("生成管线路由不能为空");
        }
        route = route.trim();
        reason = normalize(reason);
        resultSummary = normalize(resultSummary);
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

    public static GenerationPipelineOutcome completed(String route,
                                                       GenerationTaskStatus status,
                                                       String reason,
                                                       String resultSummary) {
        return new GenerationPipelineOutcome(
                GenerationPipelineDisposition.COMPLETED, route, status, reason, resultSummary);
    }

    public static GenerationPipelineOutcome running(String route) {
        return new GenerationPipelineOutcome(
                GenerationPipelineDisposition.RUNNING, route, null, null, null);
    }

    public static GenerationPipelineOutcome fallback(String route, String reason) {
        return new GenerationPipelineOutcome(
                GenerationPipelineDisposition.FALLBACK, route, null, reason, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
