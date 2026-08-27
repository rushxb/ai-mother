package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将模型分析与系统拥有的证据状态分离。
 *
 * <p>模型只能产生 {@link ReadOnlyAnalysisResult}，不能自行声明项目是否可审计、
 * 证据来自仓库还是用户需求，也不能伪造上下文版本。</p>
 */
public record ReadOnlyAnalysisOutcome(
        ReadOnlyAnalysisResult analysis,
        ReadOnlyAnalysisStatus status,
        ReadOnlyEvidenceBasis evidenceBasis,
        String unavailableReason,
        ProtectedRepositoryContextEnvelope contextEnvelope
) {

    public ReadOnlyAnalysisOutcome {
        if (analysis == null) {
            throw new IllegalArgumentException("只读分析结果不能为空");
        }
        status = status == null ? ReadOnlyAnalysisStatus.COMPLETED : status;
        evidenceBasis = evidenceBasis == null
                ? ReadOnlyEvidenceBasis.NO_REPOSITORY_CONTEXT : evidenceBasis;
        unavailableReason = unavailableReason == null ? "" : unavailableReason.trim();
        if (contextEnvelope == null) {
            throw new IllegalArgumentException("只读分析上下文信封不能为空");
        }
        if (status != ReadOnlyAnalysisStatus.COMPLETED && unavailableReason.isBlank()) {
            throw new IllegalArgumentException("不可分析状态必须提供结构化原因");
        }
    }

    public static ReadOnlyAnalysisOutcome completed(
            ReadOnlyAnalysisResult analysis,
            ReadOnlyEvidenceBasis evidenceBasis,
            ProtectedRepositoryContextEnvelope contextEnvelope) {
        return new ReadOnlyAnalysisOutcome(
                analysis,
                ReadOnlyAnalysisStatus.COMPLETED,
                evidenceBasis,
                "",
                contextEnvelope
        );
    }

    public static ReadOnlyAnalysisOutcome unavailable(
            ReadOnlyAnalysisStatus status,
            String summary,
            String reason,
            ProtectedRepositoryContextEnvelope contextEnvelope) {
        if (status == null || status == ReadOnlyAnalysisStatus.COMPLETED) {
            throw new IllegalArgumentException("不可分析结果必须使用非完成状态");
        }
        ReadOnlyAnalysisResult analysis = new ReadOnlyAnalysisResult(
                summary,
                java.util.List.of(),
                java.util.List.of(),
                "本次请求为只读分析，因此未修改工作区"
        );
        return new ReadOnlyAnalysisOutcome(
                analysis,
                status,
                ReadOnlyEvidenceBasis.NO_REPOSITORY_CONTEXT,
                reason,
                contextEnvelope
        );
    }

    public ReadOnlyAnalysisOutcome requireIntentCoverage() {
        analysis.requireIntentCoverage();
        return this;
    }

    public String validationSummary() {
        return switch (status) {
            case COMPLETED -> switch (evidenceBasis) {
                case REPOSITORY_FACTS, REPOSITORY_AND_REQUIREMENT -> "项目文件引用已通过采集上下文校验";
                case USER_REQUIREMENT -> "规划结果已按冻结用户需求完成，无强制仓库引用";
                case NO_REPOSITORY_CONTEXT -> "已确认当前工作区没有可用项目上下文";
            };
            case NO_PROJECT_CONTEXT -> "已确认当前工作区没有可解释的项目文件";
            case NOT_AUDITABLE -> "已确认当前工作区没有可审计的项目文件";
        };
    }

    /** 返回持久化载荷，仅记录信任元数据，不复制受保护的完整源上下文。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>(analysis.toPayload());
        payload.put("analysisStatus", status.name());
        payload.put("evidenceBasis", evidenceBasis.name());
        if (!unavailableReason.isBlank()) {
            payload.put("unavailableReason", unavailableReason);
        }
        payload.put("contextWorkspaceVersion", contextEnvelope.workspaceVersion());
        payload.put("contextTokenBudget", contextEnvelope.tokenBudget());
        payload.put("contextEstimatedTokens", contextEnvelope.estimatedTokens());
        payload.put("contextSourceCount", contextEnvelope.sources().size());
        payload.put("contextRedacted", contextEnvelope.redacted());
        payload.put("contextTruncated", contextEnvelope.truncated());
        payload.put("contextPromptInjectionRisk", contextEnvelope.promptInjectionRisk().name());
        payload.put("contextOutboundAllowed", contextEnvelope.outboundAllowed());
        return Map.copyOf(payload);
    }
}
