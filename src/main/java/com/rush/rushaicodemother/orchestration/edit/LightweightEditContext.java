package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

/** Immutable context assembled for one lightweight-edit model call. */
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
