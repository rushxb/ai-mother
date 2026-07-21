package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

public record GenerationMemoryOutboxItem(
        String taskId,
        Long tenantId,
        Long appId,
        Long userId,
        GenerationTaskStatus status,
        String memorySummary,
        int attempts
) {
}
