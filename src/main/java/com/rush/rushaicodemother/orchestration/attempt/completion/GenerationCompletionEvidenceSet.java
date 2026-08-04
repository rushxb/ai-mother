package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一次完成判定使用的不可变证据集合。 */
public record GenerationCompletionEvidenceSet(List<GenerationCompletionEvidence> evidence) {

    public GenerationCompletionEvidenceSet {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static GenerationCompletionEvidenceSet empty() {
        return new GenerationCompletionEvidenceSet(List.of());
    }

    public static GenerationCompletionEvidenceSet of(GenerationCompletionEvidence... evidence) {
        return new GenerationCompletionEvidenceSet(evidence == null ? List.of() : List.of(evidence));
    }

    /** 为已执行成功且确有落盘变更的同步流水线创建标准证据。 */
    public static GenerationCompletionEvidenceSet successfulMutation(
            ExpectedValidationLevel validationLevel,
            String source,
            int mutationCount
    ) {
        if (mutationCount <= 0) {
            return empty();
        }
        ExpectedValidationLevel resolvedLevel = validationLevel == null
                ? ExpectedValidationLevel.FAST
                : validationLevel;
        ArrayList<GenerationCompletionEvidence> items = new ArrayList<>();
        items.add(GenerationCompletionEvidence.of(
                GenerationCompletionEvidenceType.INTENT_COVERAGE,
                source,
                "流水线已按冻结意图执行"));
        items.add(GenerationCompletionEvidence.of(
                GenerationCompletionEvidenceType.WORKSPACE_CHANGE,
                source,
                "已确认 " + mutationCount + " 项工作区变更"));
        items.add(GenerationCompletionEvidence.of(
                GenerationCompletionEvidenceType.FAST_VALIDATION,
                source,
                "快速校验已通过"));
        if (resolvedLevel == ExpectedValidationLevel.BUILD
                || resolvedLevel == ExpectedValidationLevel.EXPERT) {
            items.add(GenerationCompletionEvidence.of(
                    GenerationCompletionEvidenceType.BUILD_VALIDATION,
                    source,
                    "构建校验已通过"));
        }
        if (resolvedLevel == ExpectedValidationLevel.EXPERT) {
            items.add(GenerationCompletionEvidence.of(
                    GenerationCompletionEvidenceType.EXPERT_VALIDATION,
                    source,
                    "专家级校验已通过"));
        }
        return new GenerationCompletionEvidenceSet(items);
    }

    public boolean contains(GenerationCompletionEvidenceType type) {
        return evidence.stream().anyMatch(item -> item.type() == type);
    }

    public GenerationCompletionEvidenceSet merge(Collection<GenerationCompletionEvidence> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }
        Map<GenerationCompletionEvidenceType, GenerationCompletionEvidence> merged =
                new EnumMap<>(GenerationCompletionEvidenceType.class);
        evidence.forEach(item -> merged.put(item.type(), item));
        additional.stream().filter(Objects::nonNull)
                .forEach(item -> merged.put(item.type(), item));
        return new GenerationCompletionEvidenceSet(List.copyOf(merged.values()));
    }
}
