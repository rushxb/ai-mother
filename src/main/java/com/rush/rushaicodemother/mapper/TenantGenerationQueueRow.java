package com.rush.rushaicodemother.mapper;

/** MyBatis 租户生成排队聚合行。 */
public record TenantGenerationQueueRow(
        int queuedTasks,
        int runningTasks,
        int waitingApprovalTasks,
        int totalNonTerminalTasks,
        int heavyNonTerminalTasks
) {
}
