package com.rush.rushaicodemother.mapper;

import java.time.LocalDateTime;

/** Prompt 灰度 SQL 聚合使用的已校验查询参数。 */
public record PromptCanaryObservationQuery(
        String promptKey,
        String stableVersion,
        String stableContentHash,
        String canaryVersion,
        String canaryContentHash,
        String bundleId,
        String intentSignature,
        String decisionVersion,
        String stableReleaseIdentity,
        String canaryReleaseIdentity,
        LocalDateTime windowStart,
        LocalDateTime windowEnd
) {
}
