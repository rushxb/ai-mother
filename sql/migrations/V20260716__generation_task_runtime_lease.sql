-- 生成任务 durable runtime、worker lease 与过期任务回收元数据迁移（MySQL 8.x）。
-- 迁移前应停止旧版本应用写入；本迁移只终结孤儿任务，不声明支持 checkpoint 断点续跑。

SET @generation_task_route_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'route'
);
SET @generation_task_route_sql = IF(
    @generation_task_route_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN route varchar(64) null comment ''运行时路由'' AFTER orchestrationMode',
    'SELECT 1'
);
PREPARE generation_task_route_statement FROM @generation_task_route_sql;
EXECUTE generation_task_route_statement;
DEALLOCATE PREPARE generation_task_route_statement;

SET @generation_task_submitted_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'submittedAt'
);
SET @generation_task_submitted_at_sql = IF(
    @generation_task_submitted_at_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN submittedAt datetime(6) null comment ''任务提交时间'' AFTER route',
    'SELECT 1'
);
PREPARE generation_task_submitted_at_statement FROM @generation_task_submitted_at_sql;
EXECUTE generation_task_submitted_at_statement;
DEALLOCATE PREPARE generation_task_submitted_at_statement;

SET @generation_task_deadline_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'deadlineAt'
);
SET @generation_task_deadline_at_sql = IF(
    @generation_task_deadline_at_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN deadlineAt datetime(6) null comment ''任务绝对截止时间'' AFTER submittedAt',
    'SELECT 1'
);
PREPARE generation_task_deadline_at_statement FROM @generation_task_deadline_at_sql;
EXECUTE generation_task_deadline_at_statement;
DEALLOCATE PREPARE generation_task_deadline_at_statement;

SET @generation_task_cancellation_requested_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'cancellationRequested'
);
SET @generation_task_cancellation_requested_sql = IF(
    @generation_task_cancellation_requested_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN cancellationRequested tinyint default 0 not null comment ''是否请求取消'' AFTER deadlineAt',
    'SELECT 1'
);
PREPARE generation_task_cancellation_requested_statement FROM @generation_task_cancellation_requested_sql;
EXECUTE generation_task_cancellation_requested_statement;
DEALLOCATE PREPARE generation_task_cancellation_requested_statement;

SET @generation_task_cancellation_reason_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'cancellationReason'
);
SET @generation_task_cancellation_reason_sql = IF(
    @generation_task_cancellation_reason_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN cancellationReason varchar(512) null comment ''取消原因'' AFTER cancellationRequested',
    'SELECT 1'
);
PREPARE generation_task_cancellation_reason_statement FROM @generation_task_cancellation_reason_sql;
EXECUTE generation_task_cancellation_reason_statement;
DEALLOCATE PREPARE generation_task_cancellation_reason_statement;

SET @generation_task_lease_owner_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'leaseOwner'
);
SET @generation_task_lease_owner_sql = IF(
    @generation_task_lease_owner_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN leaseOwner varchar(128) null comment ''worker 租约所有者'' AFTER cancellationReason',
    'SELECT 1'
);
PREPARE generation_task_lease_owner_statement FROM @generation_task_lease_owner_sql;
EXECUTE generation_task_lease_owner_statement;
DEALLOCATE PREPARE generation_task_lease_owner_statement;

SET @generation_task_lease_until_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'leaseUntil'
);
SET @generation_task_lease_until_sql = IF(
    @generation_task_lease_until_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN leaseUntil datetime(6) null comment ''worker 租约到期时间'' AFTER leaseOwner',
    'SELECT 1'
);
PREPARE generation_task_lease_until_statement FROM @generation_task_lease_until_sql;
EXECUTE generation_task_lease_until_statement;
DEALLOCATE PREPARE generation_task_lease_until_statement;

SET @generation_task_heartbeat_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'heartbeatAt'
);
SET @generation_task_heartbeat_at_sql = IF(
    @generation_task_heartbeat_at_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN heartbeatAt datetime(6) null comment ''worker 最近心跳时间'' AFTER leaseUntil',
    'SELECT 1'
);
PREPARE generation_task_heartbeat_at_statement FROM @generation_task_heartbeat_at_sql;
EXECUTE generation_task_heartbeat_at_statement;
DEALLOCATE PREPARE generation_task_heartbeat_at_statement;

SET @generation_task_attempt_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'attempt'
);
SET @generation_task_attempt_sql = IF(
    @generation_task_attempt_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN attempt int default 0 not null comment ''worker 领取次数'' AFTER heartbeatAt',
    'SELECT 1'
);
PREPARE generation_task_attempt_statement FROM @generation_task_attempt_sql;
EXECUTE generation_task_attempt_statement;
DEALLOCATE PREPARE generation_task_attempt_statement;

SET @generation_task_version_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'version'
);
SET @generation_task_version_sql = IF(
    @generation_task_version_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN version bigint default 0 not null comment ''运行时乐观锁版本'' AFTER attempt',
    'SELECT 1'
);
PREPARE generation_task_version_statement FROM @generation_task_version_sql;
EXECUTE generation_task_version_statement;
DEALLOCATE PREPARE generation_task_version_statement;

-- 将既有 trace 记录补齐为可查询的 durable runtime 记录；旧任务没有可恢复 payload/checkpoint。
UPDATE generation_task
SET route = COALESCE(route, orchestrationMode),
    submittedAt = COALESCE(submittedAt, startTime, createTime, CURRENT_TIMESTAMP(6)),
    cancellationRequested = COALESCE(cancellationRequested, 0),
    attempt = CASE
        WHEN status = 'running' THEN GREATEST(COALESCE(attempt, 0), 1)
        ELSE COALESCE(attempt, 0)
    END,
    version = COALESCE(version, 0)
WHERE isDelete = 0;

ALTER TABLE generation_task
    MODIFY COLUMN status varchar(32) default 'queued' not null
        comment '状态：queued/running/success/failed/cancelled/deadline_exceeded',
    MODIFY COLUMN submittedAt datetime(6) not null comment '任务提交时间',
    MODIFY COLUMN cancellationRequested tinyint default 0 not null comment '是否请求取消',
    MODIFY COLUMN attempt int default 0 not null comment 'worker 领取次数',
    MODIFY COLUMN version bigint default 0 not null comment '运行时乐观锁版本';

SET @generation_task_runtime_lease_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_runtime_lease'
);
SET @generation_task_runtime_lease_index_sql = IF(
    @generation_task_runtime_lease_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_runtime_lease (status, leaseUntil, isDelete)',
    'SELECT 1'
);
PREPARE generation_task_runtime_lease_index_statement
    FROM @generation_task_runtime_lease_index_sql;
EXECUTE generation_task_runtime_lease_index_statement;
DEALLOCATE PREPARE generation_task_runtime_lease_index_statement;

SET @generation_task_app_runtime_status_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_app_runtime_status'
);
SET @generation_task_app_runtime_status_index_sql = IF(
    @generation_task_app_runtime_status_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_app_runtime_status (appId, status, submittedAt)',
    'SELECT 1'
);
PREPARE generation_task_app_runtime_status_index_statement
    FROM @generation_task_app_runtime_status_index_sql;
EXECUTE generation_task_app_runtime_status_index_statement;
DEALLOCATE PREPARE generation_task_app_runtime_status_index_statement;
