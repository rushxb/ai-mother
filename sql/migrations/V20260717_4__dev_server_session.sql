create table if not exists dev_server_session
(
    appId              bigint                                    not null comment 'application id' primary key,
    userId             bigint                                    not null comment 'owner user id',
    nodeId             varchar(128)                              not null comment 'stable deployment node id',
    leaseOwner         varchar(160)                              null comment 'process-unique lease owner',
    state              varchar(32)                               not null comment 'session lifecycle state',
    port               int                                       not null comment 'preview port on owner node',
    projectDirectory   varchar(1024)                             not null comment 'normalized generated project path',
    sandboxBackend     varchar(32)                               null comment 'sandbox backend owning resources',
    cleanupResourceIds varchar(2048)                             null comment 'newline-delimited opaque sandbox resource ids',
    leaseUntil         datetime(6)                               null comment 'ownership lease expiration',
    heartbeatAt        datetime(6)                               null comment 'last successful owner heartbeat',
    version            bigint          default 0                 not null comment 'optimistic fencing version',
    lastError          varchar(512)                              null comment 'sanitized lifecycle failure',
    createTime         datetime(6)     default CURRENT_TIMESTAMP(6) not null,
    updateTime         datetime(6)     default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    key idx_dev_server_session_user_state_lease (userId, state, leaseUntil),
    key idx_dev_server_session_state_lease (state, leaseUntil),
    constraint chk_dev_server_session_port check (port between 1 and 65535)
) comment 'durable Dev Server ownership and orphan recovery registry' collate = utf8mb4_unicode_ci;
