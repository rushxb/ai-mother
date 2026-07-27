package com.rush.rushaicodemother.memory;

/**
 * 语义记忆删除事务发件箱条目的不可变数据载体。
 */
public record SemanticMemoryDeletionOutboxItem(
        String operationId,
        Long tenantId,
        Long appId,
        Long requestedByUserId,
        int attempts
) {
}
