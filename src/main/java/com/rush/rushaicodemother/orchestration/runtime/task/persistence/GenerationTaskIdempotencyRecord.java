package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

/** Minimal persisted submission identity needed to resolve an idempotent retry. */
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
