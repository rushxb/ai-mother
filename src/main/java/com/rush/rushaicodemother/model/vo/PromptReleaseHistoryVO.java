package com.rush.rushaicodemother.model.vo;

import java.time.Instant;

/**
 * 提示词发布历史接口视图对象。
 */
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
