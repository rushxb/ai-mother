package com.rush.rushaicodemother.orchestration.runtime.task;

/** 事务准入结果用于防止幂等重放后的重复调度。 */
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
