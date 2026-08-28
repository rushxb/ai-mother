package com.rush.rushaicodemother.orchestration.delivery;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidence;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceType;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** 从终态已观察事实生成唯一公开交付回执。 */
public final class GenerationDeliveryReceiptFactory {

    private GenerationDeliveryReceiptFactory() {
    }

    public static GenerationDeliveryReceipt fromTerminal(
            String actualRoute,
            GenerationTaskStatus status,
            GenerationCompletionEvidenceSet completionEvidence,
            GenerationOutcomeQuality outcomeQuality) {
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("交付回执必须基于任务终态");
        }
        GenerationOutcomeQuality quality = outcomeQuality == null
                ? GenerationOutcomeQuality.empty() : outcomeQuality;
        GenerationFailureRecoveryAdvisor.Advice advice =
                GenerationFailureRecoveryAdvisor.advise(status, quality.failureCategory());
        return new GenerationDeliveryReceipt(
                GenerationDeliveryReceipt.CURRENT_SCHEMA_VERSION,
                normalizeRoute(actualRoute),
                changeSummary(quality.changedFileCount()),
                validationSummary(status, completionEvidence),
                previewMaturity(status, quality.firstPreviewMillis()),
                quality.firstPreviewMillis(),
                advice.failureCategory(),
                advice.retryable(),
                advice.recoveryAction(),
                advice.nextStep(),
                GenerationCostSummary.pending()
        );
    }

    private static GenerationChangeSummary changeSummary(Integer changedFileCount) {
        String summary = changedFileCount == null
                ? "变更文件数量未采集"
                : changedFileCount == 0
                ? "未修改项目文件"
                : "已变更 " + changedFileCount + " 个项目文件";
        return new GenerationChangeSummary(changedFileCount, summary);
    }

    private static GenerationValidationSummary validationSummary(
            GenerationTaskStatus status,
            GenerationCompletionEvidenceSet completionEvidence) {
        EnumSet<GenerationCompletionEvidenceType> observed =
                EnumSet.noneOf(GenerationCompletionEvidenceType.class);
        if (completionEvidence != null && completionEvidence.evidence() != null) {
            completionEvidence.evidence().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(GenerationCompletionEvidence::type)
                    .forEach(observed::add);
        }
        List<String> evidenceTypes = observed.stream()
                .map(type -> type.name().toLowerCase(Locale.ROOT))
                .toList();
        String highestLevel = highestLevel(observed);
        boolean validationObserved = observed.contains(GenerationCompletionEvidenceType.FAST_VALIDATION)
                || observed.contains(GenerationCompletionEvidenceType.BUILD_VALIDATION)
                || observed.contains(GenerationCompletionEvidenceType.EXPERT_VALIDATION);
        String validationStatus;
        String summary;
        if (status == GenerationTaskStatus.SUCCESS && validationObserved) {
            validationStatus = "passed";
            summary = "已通过 " + levelLabel(highestLevel) + " 验证";
        } else if (status == GenerationTaskStatus.SUCCESS) {
            validationStatus = "not_observed";
            summary = "任务已成功，但未采集结构化验证证据";
        } else if (status == GenerationTaskStatus.CANCELLED
                || status == GenerationTaskStatus.DEADLINE_EXCEEDED) {
            validationStatus = "not_completed";
            summary = "验证未完成";
        } else {
            validationStatus = "incomplete";
            summary = "验证未通过或未完成";
        }
        return new GenerationValidationSummary(
                validationStatus, highestLevel, evidenceTypes, summary);
    }

    private static String highestLevel(EnumSet<GenerationCompletionEvidenceType> observed) {
        if (observed.contains(GenerationCompletionEvidenceType.EXPERT_VALIDATION)) {
            return "expert";
        }
        if (observed.contains(GenerationCompletionEvidenceType.BUILD_VALIDATION)) {
            return "build";
        }
        if (observed.contains(GenerationCompletionEvidenceType.FAST_VALIDATION)) {
            return "fast";
        }
        return "not_observed";
    }

    private static String levelLabel(String level) {
        return switch (level) {
            case "expert" -> "专家级";
            case "build" -> "构建级";
            case "fast" -> "快速";
            default -> "结构化";
        };
    }

    private static String previewMaturity(GenerationTaskStatus status, Long firstPreviewMillis) {
        if (firstPreviewMillis == null) {
            return "none";
        }
        return status == GenerationTaskStatus.SUCCESS ? "verified" : "provisional";
    }

    private static String normalizeRoute(String route) {
        return route == null || route.isBlank() ? "unknown" : route.trim();
    }
}
