package com.rush.rushaicodemother.orchestration.runtime.task;

/** 将一个已保留的生成命令分派到已配置的工作传输。 */
public interface GenerationTaskDispatcher {
    void dispatch(String taskId);
}
