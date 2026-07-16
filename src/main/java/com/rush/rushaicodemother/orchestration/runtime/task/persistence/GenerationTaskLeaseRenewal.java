package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

/** Result of one owner-scoped lease heartbeat. */
public record GenerationTaskLeaseRenewal(
        boolean renewed,
        boolean cancellationRequested,
        String cancellationReason
) {
    public static GenerationTaskLeaseRenewal lost() {
        return new GenerationTaskLeaseRenewal(false, false, null);
    }
}
