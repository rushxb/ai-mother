package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

/** 为一次轻量级编辑模型调用组装的不可变上下文。 */
public record LightweightEditContext(
        List<EditFileCandidate> candidates,
        String projectContext,
        boolean contextAvailable
) {
    public LightweightEditContext {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        projectContext = projectContext == null ? "" : projectContext;
    }

    public static LightweightEditContext noCandidates() {
        return new LightweightEditContext(List.of(), "", false);
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }
}
