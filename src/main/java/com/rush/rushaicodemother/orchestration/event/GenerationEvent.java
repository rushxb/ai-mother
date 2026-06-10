package com.rush.rushaicodemother.orchestration.event;

import java.time.Instant;
import java.util.Map;

public record GenerationEvent(
        Long appId,
        Long userId,
        GenerationEventType type,
        String message,
        Map<String, Object> data,
        Instant occurredAt
) {
}
