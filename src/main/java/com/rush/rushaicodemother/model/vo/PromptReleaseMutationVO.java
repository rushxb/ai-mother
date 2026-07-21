package com.rush.rushaicodemother.model.vo;

public record PromptReleaseMutationVO(
        String promptKey,
        long durableRevision,
        long activeRuntimeRevision,
        String activeBundleId,
        boolean appliedLocally
) {
}
