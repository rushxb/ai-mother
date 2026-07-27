package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

/** 解决幂等重试所需的最小持久提交身份。 */
public record GenerationTaskIdempotencyRecord(
        String taskId,
        String route,
        String requestFingerprint
) {

    public GenerationTaskIdempotencyRecord {
        if (taskId == null || taskId.isBlank()
                || route == null || route.isBlank()
                || requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("persisted generation idempotency record is incomplete");
        }
    }
}
