package com.rush.rushaicodemother.orchestration.governance.audit;

import java.time.Instant;

/** 生成控制面审计事实存储边界。 */
public interface GenerationControlAuditStore {

    void start(GenerationControlAuditEvent event);

    boolean complete(String eventId,
                     GenerationControlAuditOutcome outcome,
                     String resultCode,
                     Instant completedAt);

    int deleteExpired(Instant now, int limit);
}
