package com.rush.rushaicodemother.ai.prompt.release;

import java.time.Instant;

/** 当前持久释放指针为一个提示键。 */
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
