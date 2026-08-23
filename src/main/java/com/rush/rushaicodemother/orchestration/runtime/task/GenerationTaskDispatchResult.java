package com.rush.rushaicodemother.orchestration.runtime.task;

/** 已持久化任务尝试进入配置工作传输后的统一结果。 */
public enum GenerationTaskDispatchResult {
    /** 已被当前传输接纳。 */
    SCHEDULED,
    /** 已有执行者持有任务，无需重复分派。 */
    ALREADY_ACTIVE,
    /** 任务已经终态化或无需继续分派。 */
    TERMINAL,
    /** 瞬时条件不满足，持久队列应保留任务并稍后重试。 */
    RETRY
}
