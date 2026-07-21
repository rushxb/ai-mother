package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

/** Result of one owner-and-epoch-scoped lease heartbeat. */
public record GenerationTaskLeaseRenewal(
        boolean renewed,
        GenerationTaskLease lease,
        boolean cancellationRequested,
        String cancellationReason
) {
    public GenerationTaskLeaseRenewal {
        if (renewed && lease == null) {
            throw new IllegalArgumentException("renewed lease cannot be null");
        }
        if (!renewed && lease != null) {
            throw new IllegalArgumentException("lost renewal cannot expose a lease");
        }
    }

    public static GenerationTaskLeaseRenewal renewed(GenerationTaskLease lease,
                                                      boolean cancellationRequested,
                                                      String cancellationReason) {
        return new GenerationTaskLeaseRenewal(
                true, lease, cancellationRequested, cancellationReason);
    }

    public static GenerationTaskLeaseRenewal lost() {
        return new GenerationTaskLeaseRenewal(false, null, false, null);
    }
}
