package com.rush.rushaicodemother.memory;

public record SemanticMemoryDeletionOutboxItem(
        String operationId,
        Long tenantId,
        Long appId,
        Long requestedByUserId,
        int attempts
) {
}
