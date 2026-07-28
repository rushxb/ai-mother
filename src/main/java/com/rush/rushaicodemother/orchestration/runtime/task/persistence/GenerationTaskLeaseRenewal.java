package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

/** 一个所有者和纪元范围的租赁心跳的结果。 */
public record GenerationTaskLeaseRenewal(
        boolean renewed,
        GenerationTaskLease lease,
        boolean cancellationRequested,
        String cancellationReason
) {
    /** 创建生成任务租约{@code Renewal}实例并完成必要的依赖和初始状态设置。 */
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
