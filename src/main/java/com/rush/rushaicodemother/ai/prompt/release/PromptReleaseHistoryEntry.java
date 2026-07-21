package com.rush.rushaicodemother.ai.prompt.release;

import java.time.Instant;

/** Immutable audit record for a published prompt release revision. */
public record PromptReleaseHistoryEntry(
        String promptKey,
        PromptReleaseSpec release,
        long revision,
        PromptReleaseAction action,
        Long sourceRevision,
        long updatedBy,
        String changeNote,
        String evidenceId,
        Instant createdAt
) {
    public PromptReleaseHistoryEntry {
        promptKey = promptKey == null ? "" : promptKey.trim();
        changeNote = changeNote == null ? "" : changeNote.trim();
        evidenceId = evidenceId == null ? "" : evidenceId.trim();
    }

    public PromptReleaseHistoryEntry(String promptKey,
                                     PromptReleaseSpec release,
                                     long revision,
                                     PromptReleaseAction action,
                                     Long sourceRevision,
                                     long updatedBy,
                                     String changeNote,
                                     Instant createdAt) {
        this(promptKey, release, revision, action, sourceRevision, updatedBy, changeNote, "", createdAt);
    }
}
