package com.rush.rushaicodemother.orchestration.runtime.task;

/** 租户、用户与应用行锁保护下读取的生成准入事实。 */
public record GenerationTaskAdmissionSnapshot(
        int userNonTerminalTasks,
        int appNonTerminalTasks,
        int tenantNonTerminalTasks,
        int tenantHeavyNonTerminalTasks,
        long tenantMonthlyCreditUsage,
        boolean appGenerationPaused,
        boolean appEmergencyStopped,
        int appMaxConcurrentTasks,
        long appMonthlyCreditUsage,
        Long appMonthlyCreditLimit
) {
    public GenerationTaskAdmissionSnapshot {
        if (userNonTerminalTasks < 0 || appNonTerminalTasks < 0 || tenantNonTerminalTasks < 0
                || tenantHeavyNonTerminalTasks < 0 || tenantMonthlyCreditUsage < 0
                || appMonthlyCreditUsage < 0) {
            throw new IllegalArgumentException("生成任务准入快照不能包含负数");
        }
        if (appMaxConcurrentTasks < 1 || appMonthlyCreditLimit != null && appMonthlyCreditLimit < 0) {
            throw new IllegalArgumentException("应用生成控制快照不合法");
        }
    }

    /** 兼容尚未读取应用控制记录的旧测试和迁移期适配器。 */
    public GenerationTaskAdmissionSnapshot(int userNonTerminalTasks,
                                           int appNonTerminalTasks,
                                           int tenantNonTerminalTasks,
                                           int tenantHeavyNonTerminalTasks,
                                           long tenantMonthlyCreditUsage) {
        this(userNonTerminalTasks, appNonTerminalTasks, tenantNonTerminalTasks,
                tenantHeavyNonTerminalTasks, tenantMonthlyCreditUsage,
                false, false, 1, 0L, null);
    }
}
