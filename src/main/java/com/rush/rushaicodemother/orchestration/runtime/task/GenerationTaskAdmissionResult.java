package com.rush.rushaicodemother.orchestration.runtime.task;

/** Transactional admission outcome used to prevent duplicate dispatch after an idempotent replay. */
public record GenerationTaskAdmissionResult(
        String taskId,
        String route,
        Disposition disposition
) {

    public GenerationTaskAdmissionResult {
        if (taskId == null || taskId.isBlank() || route == null || route.isBlank() || disposition == null) {
            throw new IllegalArgumentException("generation task admission result is incomplete");
        }
    }

    public static GenerationTaskAdmissionResult created(String taskId, String route) {
        return new GenerationTaskAdmissionResult(taskId, route, Disposition.CREATED);
    }

    public static GenerationTaskAdmissionResult reused(String taskId, String route) {
        return new GenerationTaskAdmissionResult(taskId, route, Disposition.REUSED);
    }

    public boolean created() {
        return disposition == Disposition.CREATED;
    }

    public enum Disposition {
        CREATED,
        REUSED
    }
}
