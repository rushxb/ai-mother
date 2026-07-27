package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

import java.time.Instant;
import java.util.Objects;

/** 在原子生成任务所有权声明后返回持久租约。 */
public record GenerationTaskLease(
        GenerationExecutionFence fence,
        Instant leaseUntil
) {

    public GenerationTaskLease {
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
    }

    public String taskId() {
        return fence.taskId();
    }

    public String leaseOwner() {
        return fence.leaseOwner();
    }

    public long executionEpoch() {
        return fence.executionEpoch();
    }

    public GenerationTaskLease renewedUntil(Instant renewedLeaseUntil) {
        return new GenerationTaskLease(fence, renewedLeaseUntil);
    }

    public boolean expiredAt(Instant now) {
        return now == null || !now.isBefore(leaseUntil);
    }
}
