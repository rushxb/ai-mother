package com.rush.rushaicodemother.ai.prompt.release;

/** Optimistic, audited mutation submitted to the durable release repository. */
public record PromptReleaseMutation(
        String promptKey,
        PromptReleaseSpec release,
        long expectedRevision,
        long updatedBy,
        String changeNote,
        PromptReleaseAction action,
        Long sourceRevision,
        String evidenceId
) {
    public PromptReleaseMutation {
        evidenceId = evidenceId == null ? "" : evidenceId.trim();
    }

    public PromptReleaseMutation(String promptKey,
                                 PromptReleaseSpec release,
                                 long expectedRevision,
                                 long updatedBy,
                                 String changeNote,
                                 PromptReleaseAction action,
                                 Long sourceRevision) {
        this(promptKey, release, expectedRevision, updatedBy, changeNote, action, sourceRevision, "");
    }
}
