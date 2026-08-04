package com.rush.rushaicodemother.orchestration.attempt.completion;

import java.util.List;

/** 完成门禁的稳定判定结果。 */
public record GenerationCompletionDecision(
        boolean completable,
        List<GenerationCompletionRequirement> missing,
        String summary
) {

    public GenerationCompletionDecision {
        missing = missing == null ? List.of() : List.copyOf(missing);
        summary = summary == null || summary.isBlank()
                ? (completable ? "完成证据已满足" : "完成证据不足")
                : summary.trim();
        if (completable && !missing.isEmpty()) {
            throw new IllegalArgumentException("可完成判定不能包含缺失证据");
        }
    }
}
