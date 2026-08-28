package com.rush.rushaicodemother.infrastructure.persistence.audit;

import com.rush.rushaicodemother.mapper.GenerationControlAuditMapper;
import com.rush.rushaicodemother.model.entity.GenerationControlAuditEntity;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditEvent;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditOutcome;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** MyBatis 生成控制审计事实存储。 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationControlAuditStore implements GenerationControlAuditStore {

    private static final int MAX_DELETE_BATCH_SIZE = 5000;

    private final GenerationControlAuditMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(GenerationControlAuditEvent event) {
        if (event == null || event.outcome() != GenerationControlAuditOutcome.STARTED) {
            throw new IllegalArgumentException("只能新增 STARTED 审计事件");
        }
        GenerationControlAuditEntity entity = GenerationControlAuditEntity.builder()
                .eventId(event.eventId())
                .permission(event.permission().name())
                .resourceType(event.resourceType().name())
                .resourceId(event.resourceId())
                .actorType(event.actorType().name())
                .actorUserId(event.actorUserId())
                .transport(event.transport().name())
                .outcome(event.outcome().name())
                .resultCode(null)
                .startedAt(toLocal(event.startedAt()))
                .completedAt(null)
                .expiresAt(toLocal(event.expiresAt()))
                .build();
        if (mapper.insertStarted(entity) != 1) {
            throw new IllegalStateException("生成控制审计开始事实写入失败");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(String eventId,
                            GenerationControlAuditOutcome outcome,
                            String resultCode,
                            Instant completedAt) {
        if (eventId == null || !eventId.matches("[0-9a-f-]{36}")
                || outcome == null || !outcome.isTerminal()
                || resultCode == null || !resultCode.matches("[A-Z0-9_]{1,64}")
                || completedAt == null) {
            throw new IllegalArgumentException("生成控制审计完成事实不合法");
        }
        return mapper.complete(
                eventId, outcome.name(), resultCode, toLocal(completedAt)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired(Instant now, int limit) {
        if (now == null || limit <= 0 || limit > MAX_DELETE_BATCH_SIZE) {
            throw new IllegalArgumentException("生成控制审计清理参数不合法");
        }
        return mapper.deleteExpired(toLocal(now), limit);
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, databaseZone);
    }
}
