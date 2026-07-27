package com.rush.rushaicodemother.service.trace;

/** 同一生成任务初始化或路由迁移后的持久化结果。 */
public enum GenerationTaskTraceStartResult {

    STARTED,
    REUSED,
    TRANSITIONED;

    public boolean shouldRecordUserMessage() {
        return this == STARTED;
    }
}
