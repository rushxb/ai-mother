package com.rush.rushaicodemother.ai.prompt.release;

import java.time.Instant;

/** Current durable release pointer for one prompt key. */
public record PromptReleaseRecord(
        String promptKey,
        PromptReleaseSpec release,
        long revision,
        long updatedBy,
        String changeNote,
        Instant updatedAt
) {
    public PromptReleaseRecord {
        promptKey = promptKey == null ? "" : promptKey.trim();
        changeNote = changeNote == null ? "" : changeNote.trim();
    }
}
