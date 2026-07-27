package com.rush.rushaicodemother.orchestration.runtime.task;

/** 强制执行一项运行时管理的生成任务的绝对期限。 */
public interface GenerationTaskWatchdog {

    Registration watch(GenerationTaskExecution execution, Runnable interruptRunningTask);

    /** 在工作人员到达终端边界后取消待处理的截止时间回调。 */
    @FunctionalInterface
    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
