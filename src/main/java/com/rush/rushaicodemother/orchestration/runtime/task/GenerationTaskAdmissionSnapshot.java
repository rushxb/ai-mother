package com.rush.rushaicodemother.orchestration.runtime.task;

/** 租户行锁保护下读取的生成准入事实。 */
public record GenerationTaskAdmissionSnapshot(
        int userNonTerminalTasks,
        int tenantNonTerminalTasks,
        int tenantHeavyNonTerminalTasks,
        long tenantMonthlyCreditUsage
) {
    public GenerationTaskAdmissionSnapshot {
        if (userNonTerminalTasks < 0 || tenantNonTerminalTasks < 0
                || tenantHeavyNonTerminalTasks < 0 || tenantMonthlyCreditUsage < 0) {
            throw new IllegalArgumentException("生成任务准入快照不能包含负数");
        }
    }
}
