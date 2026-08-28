-- 生成控制面脱敏审计：先记录 STARTED，再单向终结为有界结果。
create table if not exists generation_control_audit_event
(
    id           bigint auto_increment primary key,
    eventId      char(36)                              not null,
    permission   varchar(64)                           not null,
    resourceType varchar(32)                           not null,
    resourceId   varchar(128)                          not null,
    actorType    varchar(16)                           not null,
    actorUserId  bigint                                null,
    transport    varchar(16)                           not null,
    outcome      varchar(16)                           not null,
    resultCode   varchar(64)                           null,
    startedAt    datetime(6)                           not null,
    completedAt  datetime(6)                           null,
    expiresAt    datetime(6)                           not null,
    createTime   datetime(6) default CURRENT_TIMESTAMP(6) not null,
    unique key uk_generation_control_audit_event_id (eventId),
    index idx_generation_control_audit_resource
        (resourceType, resourceId, startedAt),
    index idx_generation_control_audit_actor
        (actorUserId, startedAt),
    index idx_generation_control_audit_expiry
        (expiresAt, id),
    constraint chk_generation_control_audit_actor
        check ((actorType = 'USER' and actorUserId > 0)
            or (actorType in ('ANONYMOUS', 'SYSTEM') and actorUserId is null)),
    constraint chk_generation_control_audit_transport
        check (transport in ('HTTP', 'INTERNAL')),
    constraint chk_generation_control_audit_outcome
        check (outcome in ('STARTED', 'SUCCESS', 'DENIED', 'REJECTED', 'FAILED')),
    constraint chk_generation_control_audit_completion
        check ((outcome = 'STARTED' and completedAt is null and resultCode is null)
            or (outcome <> 'STARTED' and completedAt is not null and resultCode is not null)),
    constraint chk_generation_control_audit_time
        check (expiresAt > startedAt and (completedAt is null or completedAt >= startedAt))
) comment 'retained and sanitized generation control audit events'
    collate = utf8mb4_unicode_ci;
