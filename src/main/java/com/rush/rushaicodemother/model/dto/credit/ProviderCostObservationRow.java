package com.rush.rushaicodemother.model.dto.credit;

import lombok.Data;

/** MyBatis 专用的生成任务 Provider 成本聚合行。 */
@Data
public class ProviderCostObservationRow {

    private Long successfulTokens;

    private Long cancelledTokens;

    private Long timedOutTokens;

    private Long failedTokens;

    private Long pendingAttemptCount;
}
