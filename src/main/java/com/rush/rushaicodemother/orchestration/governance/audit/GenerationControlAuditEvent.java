package com.rush.rushaicodemother.orchestration.governance.audit;

import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;

import java.time.Instant;

/** 生成控制面的脱敏审计事件。 */
public record GenerationControlAuditEvent(
        String eventId,
        GenerationControlPermission permission,
        GenerationControlAuditResource resourceType,
        String resourceId,
        GenerationControlAuditSubject.ActorType actorType,
        Long actorUserId,
        GenerationControlAuditSubject.Transport transport,
        GenerationControlAuditOutcome outcome,
        String resultCode,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt
) {

    public GenerationControlAuditEvent {
        if (eventId == null || !eventId.matches("[0-9a-f-]{36}")
                || permission == null || resourceType == null
                || resourceId == null || resourceId.isBlank() || resourceId.length() > 128
                || actorType == null || transport == null || outcome == null
                || startedAt == null || expiresAt == null || !expiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("生成控制审计事件不完整");
        }
        new GenerationControlAuditSubject(actorType, actorUserId, transport);
        if (outcome == GenerationControlAuditOutcome.STARTED) {
            if (completedAt != null || resultCode != null) {
                throw new IllegalArgumentException("STARTED 审计事件不得伪造完成事实");
            }
        } else if (completedAt == null || completedAt.isBefore(startedAt)
                || resultCode == null || !resultCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("终态审计事件缺少有界结果");
        }
    }
}
