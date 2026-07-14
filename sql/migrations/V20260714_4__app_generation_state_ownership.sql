-- 应用生成状态所有权迁移（MySQL 8.x）。
-- 通过任务 ID 和有界租约阻止旧任务覆盖新任务状态，并允许进程丢失后的超时恢复。
-- 迁移应在旧版本应用停止写入后执行。

SET @app_generating_task_id_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app'
      AND column_name = 'generatingTaskId'
);
SET @app_generating_task_id_sql = IF(
    @app_generating_task_id_exists = 0,
    'ALTER TABLE app ADD COLUMN generatingTaskId varchar(128) null comment ''当前生成状态所有者任务 ID'' AFTER generatingStage',
    'SELECT 1'
);
PREPARE app_generating_task_id_statement FROM @app_generating_task_id_sql;
EXECUTE app_generating_task_id_statement;
DEALLOCATE PREPARE app_generating_task_id_statement;

SET @app_generation_lease_until_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app'
      AND column_name = 'generationLeaseUntil'
);
SET @app_generation_lease_until_sql = IF(
    @app_generation_lease_until_exists = 0,
    'ALTER TABLE app ADD COLUMN generationLeaseUntil datetime(6) null comment ''生成状态租约到期时间'' AFTER generatingTaskId',
    'SELECT 1'
);
PREPARE app_generation_lease_until_statement FROM @app_generation_lease_until_sql;
EXECUTE app_generation_lease_until_statement;
DEALLOCATE PREPARE app_generation_lease_until_statement;

ALTER TABLE app
    MODIFY COLUMN isGenerating tinyint default 0 not null comment '是否正在生成',
    MODIFY COLUMN generatingStage varchar(64) null comment '当前生成阶段',
    MODIFY COLUMN generatingTaskId varchar(128) null comment '当前生成状态所有者任务 ID',
    MODIFY COLUMN generationLeaseUntil datetime(6) null comment '生成状态租约到期时间';

-- 旧版本没有任务所有者字段；发布窗口内旧进程已停止，因此这些状态只能作为残留状态清理。
UPDATE app
SET isGenerating = 0,
    generatingMessage = '',
    generatingStage = NULL,
    generatingTaskId = NULL,
    generationLeaseUntil = NULL
WHERE isGenerating NOT IN (0, 1)
   OR (isGenerating = 1 AND (generatingTaskId IS NULL OR generationLeaseUntil IS NULL))
   OR (isGenerating = 0 AND (generatingTaskId IS NOT NULL OR generationLeaseUntil IS NOT NULL));

SET @app_generation_lease_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'app'
      AND index_name = 'idx_generation_lease'
);
SET @app_generation_lease_index_sql = IF(
    @app_generation_lease_index_exists = 0,
    'ALTER TABLE app ADD INDEX idx_generation_lease (isGenerating, generationLeaseUntil)',
    'SELECT 1'
);
PREPARE app_generation_lease_index_statement FROM @app_generation_lease_index_sql;
EXECUTE app_generation_lease_index_statement;
DEALLOCATE PREPARE app_generation_lease_index_statement;

SET @app_generation_state_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'app'
      AND constraint_name = 'chk_app_generation_state_ownership'
      AND constraint_type = 'CHECK'
);
SET @app_generation_state_check_sql = IF(
    @app_generation_state_check_exists = 0,
    'ALTER TABLE app ADD CONSTRAINT chk_app_generation_state_ownership CHECK ((isGenerating = 0 AND generatingTaskId IS NULL AND generationLeaseUntil IS NULL) OR (isGenerating = 1 AND generatingTaskId IS NOT NULL AND generationLeaseUntil IS NOT NULL))',
    'SELECT 1'
);
PREPARE app_generation_state_check_statement FROM @app_generation_state_check_sql;
EXECUTE app_generation_state_check_statement;
DEALLOCATE PREPARE app_generation_state_check_statement;
