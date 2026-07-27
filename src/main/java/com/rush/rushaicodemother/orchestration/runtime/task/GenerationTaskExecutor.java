package com.rush.rushaicodemother.orchestration.runtime.task;

/** 在 HTTP 请求线程之外执行提交的生成工作。 */
public interface GenerationTaskExecutor {

    void execute(String taskId, Runnable task);

    /**
     * 执行运行时管理的工作，并有权访问其取消和截止日期信封。
     *
     * <p>默认保持与只需要任务标识的基础设施适配器的兼容性。
     * 支持截止日期的本地执行器应该重写此方法。</p>
     */
    default void execute(GenerationTaskExecution execution, Runnable task) {
        if (execution == null) {
            throw new IllegalArgumentException("generation task execution cannot be null");
        }
        execute(execution.taskId(), task);
    }
}
