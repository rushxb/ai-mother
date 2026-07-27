package com.rush.rushaicodemother.model.vo;

import java.util.List;

/**
 * 提示词目录管理端接口视图对象。
 */
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
