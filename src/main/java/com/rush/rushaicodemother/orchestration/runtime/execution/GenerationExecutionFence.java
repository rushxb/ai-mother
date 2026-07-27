package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * 不可变的工作人员身份用于拒绝过期生成执行带来的副作用。
 *
 * <p>每当执行所有权时，持久任务存储的纪元单调增加
 * 已发布或撤销。仅当所有三个值仍然匹配时，工作人员写入才有效
 * 持续租约.</p>
 */
public record GenerationExecutionFence(
        String taskId,
        String leaseOwner,
        long executionEpoch
) {

    public GenerationExecutionFence {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner cannot be blank");
        }
        if (executionEpoch <= 0) {
            throw new IllegalArgumentException("executionEpoch must be positive");
        }
    }
}
