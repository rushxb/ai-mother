package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;

/**
 * 当任务范围的截止日期不再允许启动或继续操作时引发。
 */
public class GenerationDeadlineExceededException extends GenerationExecutionPolicyException {

    public GenerationDeadlineExceededException(String taskId) {
        super("生成任务已超过总时限，taskId=" + taskId);
    }

    public GenerationDeadlineExceededException(String taskId,
                                               String stage,
                                               Duration remaining,
                                               Duration required) {
        super("生成任务剩余时间不足，无法启动阶段，taskId=" + taskId
                + ", stage=" + normalizeStage(stage)
                + ", remainingMs=" + toMillis(remaining)
                + ", requiredMs=" + toMillis(required));
    }

    private static String normalizeStage(String stage) {
        return stage == null || stage.isBlank() ? "unknown" : stage.trim();
    }

    private static long toMillis(Duration duration) {
        return duration == null ? 0L : Math.max(0L, duration.toMillis());
    }
}
