package com.rush.rushaicodemother.infrastructure.redis;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ServiceUnavailableException;

/**
 * Redis 故障期间，待回灌对话记忆已占满配置容量。
 */
public class ChatMemoryFallbackCapacityExceededException extends ServiceUnavailableException {

    public ChatMemoryFallbackCapacityExceededException(long maximumEntries) {
        super(
                ErrorCode.SERVICE_UNAVAILABLE_ERROR.getMessage(),
                "Pending chat-memory mutations reached the configured in-memory capacity of "
                        + maximumEntries
                        + "; restore Redis or increase app.chat-memory.fallback-max-entries"
        );
    }
}
