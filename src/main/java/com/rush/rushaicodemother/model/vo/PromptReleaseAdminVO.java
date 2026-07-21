package com.rush.rushaicodemother.model.vo;

import java.time.Instant;
import java.util.List;

public record PromptReleaseAdminVO(
        String promptKey,
        String stableVersion,
        String stableContentHash,
        String canaryVersion,
        String canaryContentHash,
        int canaryPercentage,
        long durableRevision,
        Long updatedBy,
        String changeNote,
        Instant updatedAt,
        List<PromptVersionAdminVO> availableVersions
) {
    public PromptReleaseAdminVO {
        availableVersions = availableVersions == null ? List.of() : List.copyOf(availableVersions);
    }
}
