-- GenerationTrace 模块完整性迁移（MySQL 8.x）。
-- 为既有数据库补齐任务记忆、阶段消息、积分结算字段和模型调用幂等约束。

SET @generation_task_stage_message_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'stageMessage'
);
SET @generation_task_stage_message_sql = IF(
    @generation_task_stage_message_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN stageMessage text null comment ''当前阶段提示信息'' AFTER stage',
    'SELECT 1'
);
PREPARE generation_task_stage_message_statement FROM @generation_task_stage_message_sql;
EXECUTE generation_task_stage_message_statement;
DEALLOCATE PREPARE generation_task_stage_message_statement;

SET @generation_task_memory_summary_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'memorySummary'
);
SET @generation_task_memory_summary_sql = IF(
    @generation_task_memory_summary_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN memorySummary mediumtext null comment ''AI 可读的生成记忆摘要'' AFTER errorMessage',
    'SELECT 1'
);
PREPARE generation_task_memory_summary_statement FROM @generation_task_memory_summary_sql;
EXECUTE generation_task_memory_summary_statement;
DEALLOCATE PREPARE generation_task_memory_summary_statement;

SET @generation_task_total_tokens_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'totalTokens'
);
SET @generation_task_total_tokens_sql = IF(
    @generation_task_total_tokens_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN totalTokens bigint default 0 not null comment ''任务累计 token 数'' AFTER memorySummary',
    'SELECT 1'
);
PREPARE generation_task_total_tokens_statement FROM @generation_task_total_tokens_sql;
EXECUTE generation_task_total_tokens_statement;
DEALLOCATE PREPARE generation_task_total_tokens_statement;

SET @generation_task_credit_cost_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'creditCost'
);
SET @generation_task_credit_cost_sql = IF(
    @generation_task_credit_cost_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN creditCost bigint default 0 not null comment ''任务消耗积分'' AFTER totalTokens',
    'SELECT 1'
);
PREPARE generation_task_credit_cost_statement FROM @generation_task_credit_cost_sql;
EXECUTE generation_task_credit_cost_statement;
DEALLOCATE PREPARE generation_task_credit_cost_statement;

SET @generation_task_credit_charged_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'creditCharged'
);
SET @generation_task_credit_charged_sql = IF(
    @generation_task_credit_charged_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN creditCharged tinyint default 0 not null comment ''是否已结算积分'' AFTER creditCost',
    'SELECT 1'
);
PREPARE generation_task_credit_charged_statement FROM @generation_task_credit_charged_sql;
EXECUTE generation_task_credit_charged_statement;
DEALLOCATE PREPARE generation_task_credit_charged_statement;

SET @generation_model_call_usage_source_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_model_call'
      AND column_name = 'usageSource'
);
SET @generation_model_call_usage_source_sql = IF(
    @generation_model_call_usage_source_exists = 0,
    'ALTER TABLE generation_model_call ADD COLUMN usageSource varchar(32) default ''OFFICIAL'' not null comment ''token 来源：OFFICIAL/ESTIMATED'' AFTER finishReason',
    'SELECT 1'
);
PREPARE generation_model_call_usage_source_statement FROM @generation_model_call_usage_source_sql;
EXECUTE generation_model_call_usage_source_statement;
DEALLOCATE PREPARE generation_model_call_usage_source_statement;

SET @generation_model_call_call_id_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_model_call'
      AND column_name = 'callId'
);
SET @generation_model_call_call_id_sql = IF(
    @generation_model_call_call_id_exists = 0,
    'ALTER TABLE generation_model_call ADD COLUMN callId varchar(36) null comment ''模型调用幂等 ID'' AFTER id',
    'SELECT 1'
);
PREPARE generation_model_call_call_id_statement FROM @generation_model_call_call_id_sql;
EXECUTE generation_model_call_call_id_statement;
DEALLOCATE PREPARE generation_model_call_call_id_statement;

UPDATE generation_model_call
SET callId = UUID()
WHERE callId IS NULL
   OR TRIM(callId) = '';

UPDATE generation_model_call AS duplicate_call
JOIN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY callId ORDER BY id ASC) AS duplicate_rank
        FROM generation_model_call
    ) AS ranked_calls
    WHERE duplicate_rank > 1
) AS duplicate_ids ON duplicate_ids.id = duplicate_call.id
SET duplicate_call.callId = UUID();

ALTER TABLE generation_model_call
    MODIFY COLUMN callId varchar(36) not null comment '模型调用幂等 ID';

SET @generation_model_call_call_id_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_model_call'
      AND index_name = 'uk_callId'
);
SET @generation_model_call_call_id_index_sql = IF(
    @generation_model_call_call_id_index_exists = 0,
    'ALTER TABLE generation_model_call ADD UNIQUE KEY uk_callId (callId)',
    'SELECT 1'
);
PREPARE generation_model_call_call_id_index_statement
    FROM @generation_model_call_call_id_index_sql;
EXECUTE generation_model_call_call_id_index_statement;
DEALLOCATE PREPARE generation_model_call_call_id_index_statement;
