package com.rush.rushaicodemother.orchestration.finalization;

/**
 * 终态提交后需要独立回执的幂等副作用。
 *
 * <p>位标识持久到任务 outbox，使重试只执行尚未完成的操作。
 * 新增副作用只需增加枚举值和执行器分支，无需增加数据库列。</p>
 */
public enum GenerationTerminalEffectOperation {

    EVENT_PUBLISH("event", 1L),
    TASK_STREAM_COMPLETE("task_stream", 1L << 1),
    PREVIEW_STOP("preview", 1L << 2),
    WORKSPACE_CLEAR("workspace", 1L << 3);

    private static final long REQUIRED_MASK = computeRequiredMask();

    private final String metricName;
    private final long mask;

    GenerationTerminalEffectOperation(String metricName, long mask) {
        this.metricName = metricName;
        this.mask = mask;
    }

    public String metricName() {
        return metricName;
    }

    public long mask() {
        return mask;
    }

    public static long requiredMask() {
        return REQUIRED_MASK;
    }

    private static long computeRequiredMask() {
        long result = 0L;
        for (GenerationTerminalEffectOperation operation : values()) {
            result |= operation.mask;
        }
        return result;
    }
}
