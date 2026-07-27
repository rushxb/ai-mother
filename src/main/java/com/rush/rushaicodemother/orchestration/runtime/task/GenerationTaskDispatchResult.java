package com.rush.rushaicodemother.orchestration.runtime.task;

/** 尝试将一个持久命令接纳到本地工作运行时的结果。 */
public enum GenerationTaskDispatchResult {
    SCHEDULED,
    ALREADY_ACTIVE,
    TERMINAL,
    RETRY
}
