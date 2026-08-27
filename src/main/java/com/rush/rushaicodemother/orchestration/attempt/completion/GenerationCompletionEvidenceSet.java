package com.rush.rushaicodemother.orchestration.attempt.completion;

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
