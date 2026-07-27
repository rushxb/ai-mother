package com.rush.rushaicodemother.ai.prompt.release;

/** 乐观的、经过审核的突变已提交到持久发布存储库。 */
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
