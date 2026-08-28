package com.rush.rushaicodemother.orchestration.governance.audit;

import java.time.Instant;

/** 已持久 STARTED 事件的不可变句柄。 */
public record GenerationControlAuditHandle(
        String eventId,
        Instant startedAt,
        Instant expiresAt
) {

    public GenerationControlAuditHandle {
        if (eventId == null || !eventId.matches("[0-9a-f-]{36}")
                || startedAt == null || expiresAt == null || !expiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("审计事件句柄不合法");
        }
    }
}
