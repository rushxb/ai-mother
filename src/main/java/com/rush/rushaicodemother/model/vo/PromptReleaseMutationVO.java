package com.rush.rushaicodemother.model.vo;

/**
 * 提示词发布变更接口视图对象。
 */
public record PromptReleaseMutationVO(
        String promptKey,
        long durableRevision,
        long activeRuntimeRevision,
        String activeBundleId,
        boolean appliedLocally
) {
}
