-- 终态副作用 dead-letter 的精确人工重放审计。
create table if not exists generation_terminal_effect_replay_audit
(
    id               bigint auto_increment primary key,
    auditId          char(36)                           not null,
    taskId           varchar(128)                       not null,
    executionEpoch   bigint                             not null,
    previousAttempts int                                not null,
    operatorUserId   bigint                             not null,
    requestedAt      datetime(6)                        not null,
    createTime       datetime(6) default CURRENT_TIMESTAMP(6) not null,
    unique key uk_generation_terminal_effect_replay_audit_id (auditId),
    index idx_generation_terminal_effect_replay_task
        (taskId, executionEpoch, requestedAt),
    constraint chk_generation_terminal_effect_replay_epoch
        check (executionEpoch > 0),
    constraint chk_generation_terminal_effect_replay_attempts
        check (previousAttempts > 0),
    constraint chk_generation_terminal_effect_replay_operator
        check (operatorUserId > 0)
) comment 'immutable terminal effect dead-letter replay audit' collate = utf8mb4_unicode_ci;
