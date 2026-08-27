package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;

import java.util.List;

/** 为一次轻量级编辑模型调用组装的不可变上下文。 */
public record LightweightEditContext(
        List<EditFileCandidate> candidates,
        String projectContext,
        boolean contextAvailable,
        ProtectedRepositoryContextEnvelope contextEnvelope
) {
    public LightweightEditContext {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (contextAvailable && contextEnvelope == null) {
            throw new IllegalArgumentException("可用的轻量编辑上下文必须携带信任信封");
        }
        if (!contextAvailable && contextEnvelope != null) {
            throw new IllegalArgumentException("不可用的轻量编辑上下文不得携带信任信封");
        }
        String safeProjectContext = projectContext == null ? "" : projectContext;
        projectContext = contextEnvelope == null
                ? safeProjectContext : contextEnvelope.content();
    }

    public static LightweightEditContext noCandidates() {
        return new LightweightEditContext(List.of(), "", false, null);
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }
}
