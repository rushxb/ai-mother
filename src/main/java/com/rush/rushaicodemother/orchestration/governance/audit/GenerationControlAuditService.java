package com.rush.rushaicodemother.orchestration.governance.audit;

import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/** 统一建立并终结生成控制面审计事件。 */
@Slf4j
@Service
public class GenerationControlAuditService {

    private static final String RESOURCE_ID_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    private final GenerationControlAuditStore store;
    private final GenerationControlAuditProperties properties;
    private final Clock clock;

    @Autowired
    public GenerationControlAuditService(GenerationControlAuditStore store,
                                         GenerationControlAuditProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    GenerationControlAuditService(GenerationControlAuditStore store,
                                  GenerationControlAuditProperties properties,
                                  Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 在任何受控操作前先持久 STARTED；该步骤失败时操作失败关闭。
     */
    public GenerationControlAuditHandle begin(GenerationControlPermission permission,
                                              GenerationControlAuditResource resourceType,
                                              Object resourceId,
                                              GenerationControlAuditSubject subject) {
        if (permission == null || resourceType == null || subject == null) {
            throw new IllegalArgumentException("审计操作上下文不完整");
        }
        Instant startedAt = clock.instant();
        Instant expiresAt = startedAt.plus(properties.getRetention());
        String eventId = UUID.randomUUID().toString();
        GenerationControlAuditEvent event = new GenerationControlAuditEvent(
                eventId,
                permission,
                resourceType,
                normalizeResourceId(resourceId),
                subject.actorType(),
                subject.actorUserId(),
                subject.transport(),
                GenerationControlAuditOutcome.STARTED,
                null,
                startedAt,
                null,
                expiresAt);
        store.start(event);
        return new GenerationControlAuditHandle(eventId, startedAt, expiresAt);
    }

    /** 只允许将 STARTED 单向完成为一个有界结果。 */
    public void complete(GenerationControlAuditHandle handle,
                         GenerationControlAuditOutcome outcome,
                         String resultCode) {
        if (handle == null || outcome == null || !outcome.isTerminal()
                || resultCode == null || !resultCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("审计完成事实不合法");
        }
        if (!store.complete(handle.eventId(), outcome, resultCode, clock.instant())) {
            throw new IllegalStateException("审计事件已终结或不存在");
        }
    }

    /** 对内部系统操作执行同样的先记录、后终结合同。 */
    public <T> T executeSystem(GenerationControlPermission permission,
                               GenerationControlAuditResource resourceType,
                               Object resourceId,
                               Supplier<T> operation) {
        if (operation == null) {
            throw new IllegalArgumentException("系统审计操作不能为空");
        }
        GenerationControlAuditHandle handle = begin(
                permission, resourceType, resourceId,
                GenerationControlAuditSubject.internalSystem());
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException | Error failure) {
            try {
                complete(handle, GenerationControlAuditOutcome.FAILED, "INTERNAL_ERROR");
            } catch (RuntimeException completionFailure) {
                failure.addSuppressed(completionFailure);
            }
            throw failure;
        }
        try {
            complete(handle, GenerationControlAuditOutcome.SUCCESS, "OK");
        } catch (RuntimeException completionFailure) {
            // 操作结果已知为成功，审计终结失败时保留 STARTED 供对账，不诱导调用方重放。
            log.error("系统控制审计事件未能终结，eventId: {}, failureType: {}",
                    handle.eventId(), completionFailure.getClass().getSimpleName());
        }
        return result;
    }

    private String normalizeResourceId(Object resourceId) {
        if (resourceId == null) {
            return "unresolved";
        }
        String value = String.valueOf(resourceId).trim();
        if (value.isEmpty()) {
            return "unresolved";
        }
        if (value.matches(RESOURCE_ID_PATTERN)) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
