-- Durable stage-level critical-path observations.
-- This table stores completed spans only; it is not a checkpoint or deterministic replay journal.
CREATE TABLE IF NOT EXISTS generation_task_span
(
    id         bigint auto_increment comment 'id' primary key,
    spanId     varchar(36)                         not null comment 'span 幂等 ID',
    taskId     varchar(128)                        not null comment '生成任务 ID',
    stage      varchar(96)                         not null comment '阶段标识',
    category   varchar(32)                         not null comment '阶段类别',
    status     varchar(32)                         not null comment '阶段状态',
    startedAt  datetime(6)                         not null comment '阶段开始时间',
    endedAt    datetime(6)                         not null comment '阶段结束时间',
    durationMs bigint                               not null comment '阶段耗时毫秒',
    detail     varchar(1000)                        not null default '' comment '脱敏后的简要诊断',
    createTime datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    isDelete   tinyint     default 0                not null comment '是否删除',
    UNIQUE KEY uk_spanId (spanId),
    INDEX idx_task_started (taskId, startedAt, id),
    INDEX idx_stage_duration (stage, status, durationMs)
) comment 'AI 生成关键路径 span' collate = utf8mb4_unicode_ci;
