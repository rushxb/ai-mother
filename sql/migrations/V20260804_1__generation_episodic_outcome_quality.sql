-- L3 情景记录：把单次任务的结果质量字段折叠进既有 generation_task 行。
-- 这些列全部可空，NULL 语义为「未采集」；写入随终态 UPDATE 一并完成，
-- 因此不引入独立写入路径、不新增 outbox，也不改变任务执行与恢复语义。
-- thinkingMode / reworkedAt / distilledAt 现阶段恒为 NULL，等待思考模式联动、
-- 隐式验收信号与经验蒸馏落地后才会写入。

SET @generation_task_outcome_quality_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'changedFileCount'
);
SET @generation_task_outcome_quality_sql = IF(
    @generation_task_outcome_quality_exists = 0,
    'ALTER TABLE generation_task
        ADD COLUMN thinkingMode varchar(16) NULL COMMENT ''实际使用的思考档位'' AFTER qualityGate,
        ADD COLUMN changedFileCount int NULL COMMENT ''有效变更文件数'' AFTER thinkingMode,
        ADD COLUMN firstBuildPassed tinyint NULL COMMENT ''是否免修复通过构建'' AFTER changedFileCount,
        ADD COLUMN repairRounds int NULL COMMENT ''实际修复轮次'' AFTER firstBuildPassed,
        ADD COLUMN firstPreviewMillis bigint NULL COMMENT ''提交到可预览耗时毫秒'' AFTER repairRounds,
        ADD COLUMN failureCategory varchar(64) NULL COMMENT ''失败分类'' AFTER firstPreviewMillis,
        ADD COLUMN reworkedAt datetime(6) NULL COMMENT ''交付后被追加改修的时间'' AFTER failureCategory,
        ADD COLUMN distilledAt datetime(6) NULL COMMENT ''经验已蒸馏时间'' AFTER reworkedAt',
    'SELECT 1'
);
PREPARE generation_task_outcome_quality_statement
    FROM @generation_task_outcome_quality_sql;
EXECUTE generation_task_outcome_quality_statement;
DEALLOCATE PREPARE generation_task_outcome_quality_statement;

-- 蒸馏扫描索引：列序与既有 idx_memory_outbox_claim 保持一致的租约扫描惯例。
SET @generation_task_distill_claim_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_generation_task_distill_claim'
);
SET @generation_task_distill_claim_index_sql = IF(
    @generation_task_distill_claim_index_exists = 0,
    'ALTER TABLE generation_task
        ADD INDEX idx_generation_task_distill_claim (distilledAt, status, isDelete, endTime, id)',
    'SELECT 1'
);
PREPARE generation_task_distill_claim_index_statement
    FROM @generation_task_distill_claim_index_sql;
EXECUTE generation_task_distill_claim_index_statement;
DEALLOCATE PREPARE generation_task_distill_claim_index_statement;

-- 边界约束：允许 NULL（未采集），非空时必须落在合法域内。
SET @generation_task_outcome_quality_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND constraint_name = 'chk_generation_task_outcome_quality'
);
SET @generation_task_outcome_quality_check_sql = IF(
    @generation_task_outcome_quality_check_exists = 0,
    'ALTER TABLE generation_task
        ADD CONSTRAINT chk_generation_task_outcome_quality CHECK (
            (changedFileCount IS NULL OR changedFileCount >= 0)
            AND (repairRounds IS NULL OR repairRounds >= 0)
            AND (firstPreviewMillis IS NULL OR firstPreviewMillis >= 0)
            AND (firstBuildPassed IS NULL OR firstBuildPassed IN (0, 1))
        )',
    'SELECT 1'
);
PREPARE generation_task_outcome_quality_check_statement
    FROM @generation_task_outcome_quality_check_sql;
EXECUTE generation_task_outcome_quality_check_statement;
DEALLOCATE PREPARE generation_task_outcome_quality_check_statement;
