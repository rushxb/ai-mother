package com.rush.rushaicodemother.model.vo;

import java.time.Instant;

public record PromptReleaseHistoryVO(
        String promptKey,
        String stableVersion,
        String canaryVersion,
        int canaryPercentage,
        long revision,
        String action,
        Long sourceRevision,
        long updatedBy,
        String changeNote,
        String evidenceId,
        Instant createdAt
) {
}
