CREATE TABLE IF NOT EXISTS generation_tool_approval
(
    id          bigint auto_increment primary key,
    approvalId  char(64)                            not null comment 'target-bound approval id',
    taskId      varchar(128)                        not null comment 'generation task id',
    appId       bigint                              not null comment 'application id',
    userId      bigint                              not null comment 'application owner id',
    action      varchar(64)                         not null comment 'destructive tool action',
    requestJson mediumtext                          not null comment 'normalized approval request',
    status      varchar(32) default 'pending'       not null comment 'pending/approved/rejected/executing/consumed/expired',
    requestedAt datetime(6)                         not null,
    expiresAt   datetime(6)                         not null,
    decidedBy   bigint                              null,
    decidedAt   datetime(6)                         null,
    consumedAt  datetime(6)                         null,
    executionStartedAt datetime(6)                  null comment 'current invocation execution start',
    executionResult mediumtext                      null comment 'durable replayable tool result JSON',
    executionAttempt int default 0                  not null comment 'side-effect execution attempts',
    toolRequestId varchar(128)                      null comment 'model tool invocation id',
    toolName    varchar(128)                        null comment 'model tool name',
    argumentsDigest char(64)                        null comment 'SHA-256 of tool arguments',
    checkpointJson mediumtext                       null comment 'versioned runtime continuation checkpoint',
    version     bigint      default 0               not null,
    createTime  datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime  datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_task_approval (taskId, approvalId),
    UNIQUE KEY uk_task_tool_request (taskId, toolRequestId),
    INDEX idx_approval_expiration (status, expiresAt, id),
    INDEX idx_approval_execution (status, executionStartedAt, id),
    INDEX idx_approval_app (appId, requestedAt),
    CONSTRAINT chk_generation_tool_approval_expiry CHECK (expiresAt > requestedAt),
    CONSTRAINT chk_generation_tool_approval_checkpoint CHECK (
        (toolRequestId IS NULL AND toolName IS NULL AND argumentsDigest IS NULL AND checkpointJson IS NULL)
        OR (toolRequestId IS NOT NULL AND toolName IS NOT NULL
            AND argumentsDigest IS NOT NULL AND checkpointJson IS NOT NULL)
    ),
    CONSTRAINT chk_generation_tool_approval_status CHECK (
        status in ('pending', 'approved', 'rejected', 'executing', 'consumed', 'expired')
    ),
    CONSTRAINT chk_generation_tool_approval_attempt CHECK (executionAttempt >= 0),
    CONSTRAINT chk_generation_tool_approval_state CHECK (
        (status = 'pending' AND decidedBy IS NULL AND decidedAt IS NULL AND consumedAt IS NULL
            AND executionStartedAt IS NULL AND executionResult IS NULL AND executionAttempt = 0)
        OR (status = 'approved' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NULL
            AND executionStartedAt IS NULL AND executionResult IS NULL)
        OR (status = 'rejected' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NULL
            AND executionStartedAt IS NULL AND executionResult IS NULL)
        OR (status = 'executing' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NULL
            AND executionStartedAt IS NOT NULL AND executionResult IS NULL AND executionAttempt > 0)
        OR (status = 'consumed' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NOT NULL
            AND executionStartedAt IS NOT NULL AND executionResult IS NOT NULL AND executionAttempt > 0)
        OR (status = 'expired' AND consumedAt IS NULL AND executionStartedAt IS NULL AND executionResult IS NULL)
    )
) comment 'durable one-time AI tool approval' collate = utf8mb4_unicode_ci;
