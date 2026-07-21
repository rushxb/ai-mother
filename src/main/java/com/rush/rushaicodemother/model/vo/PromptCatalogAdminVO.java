package com.rush.rushaicodemother.model.vo;

import java.util.List;

public record PromptCatalogAdminVO(
        String activeBundleId,
        long durableBundleRevision,
        long activeRuntimeRevision,
        List<PromptReleaseAdminVO> releases
) {
    public PromptCatalogAdminVO {
        releases = releases == null ? List.of() : List.copyOf(releases);
    }
}
