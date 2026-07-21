-- Durable DAG checkpoints for cross-instance orchestration recovery.

CREATE TABLE IF NOT EXISTS generation_orchestration_checkpoint
(
    id                    bigint auto_increment primary key,
    taskId                varchar(128)                         not null comment 'generation task id',
    appId                 bigint                               not null comment 'application id',
    requestHash           char(64)                             not null comment 'sha-256 hash of the original request',
    status                varchar(32)                          not null comment 'running/completed/failed',
    runtimeState          varchar(32)                          not null comment 'agent runtime state',
    currentNode           varchar(128)                         null comment 'currently running DAG node',
    lastCompletedNode     varchar(128)                         null comment 'last completed DAG node',
    checkpointVersion     bigint      default 0                not null comment 'monotonic checkpoint version',
    payloadJson           mediumtext                           not null comment 'versioned orchestration checkpoint payload',
    payloadBytes          int                                  not null comment 'serialized payload bytes',
    createTime            datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime            datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    isDelete              tinyint     default 0                not null,
    UNIQUE KEY uk_generation_orchestration_task (taskId),
    INDEX idx_generation_orchestration_app (appId, isDelete, updateTime),
    INDEX idx_generation_orchestration_state (status, runtimeState, isDelete, updateTime),
    CONSTRAINT chk_generation_orchestration_checkpoint_version CHECK (checkpointVersion >= 0),
    CONSTRAINT chk_generation_orchestration_payload_bytes CHECK (payloadBytes > 0)
) comment 'durable generation DAG checkpoint' collate = utf8mb4_unicode_ci;
