package com.rush.rushaicodemother.model.dto.credit;

import lombok.Data;

/** MyBatis 专用的生成任务模型用量聚合行。 */
@Data
public class GenerationTaskModelUsageRow {

    private Long totalTokens;

    private Long successfulCallCount;

    private Long pendingCallCount;
}
