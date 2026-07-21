-- Monotonic execution fencing for durable generation workers and application generation state.

SET @generation_task_execution_epoch_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'executionEpoch'
);
SET @generation_task_execution_epoch_sql = IF(
    @generation_task_execution_epoch_exists = 0,
    'ALTER TABLE generation_task ADD COLUMN executionEpoch bigint default 0 not null comment ''monotonic worker fencing epoch'' AFTER heartbeatAt',
    'SELECT 1'
);
PREPARE generation_task_execution_epoch_statement FROM @generation_task_execution_epoch_sql;
EXECUTE generation_task_execution_epoch_statement;
DEALLOCATE PREPARE generation_task_execution_epoch_statement;

UPDATE generation_task
SET executionEpoch = GREATEST(
        COALESCE(executionEpoch, 0),
        CASE WHEN leaseOwner IS NULL THEN 0 ELSE GREATEST(COALESCE(attempt, 0), 1) END
    )
WHERE isDelete = 0;

SET @app_generation_execution_epoch_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app'
      AND column_name = 'generationExecutionEpoch'
);
SET @app_generation_execution_epoch_sql = IF(
    @app_generation_execution_epoch_exists = 0,
    'ALTER TABLE app ADD COLUMN generationExecutionEpoch bigint null comment ''current generation fencing epoch'' AFTER generatingTaskId',
    'SELECT 1'
);
PREPARE app_generation_execution_epoch_statement FROM @app_generation_execution_epoch_sql;
EXECUTE app_generation_execution_epoch_statement;
DEALLOCATE PREPARE app_generation_execution_epoch_statement;

UPDATE app application
LEFT JOIN generation_task task
       ON task.taskId = application.generatingTaskId
      AND task.isDelete = 0
SET application.generationExecutionEpoch = CASE
        WHEN application.isGenerating = 1
            THEN GREATEST(COALESCE(task.executionEpoch, 0), 0)
        ELSE NULL
    END
WHERE application.isDelete = 0;

SET @generation_task_epoch_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'generation_task'
      AND constraint_name = 'chk_generation_task_execution_epoch'
      AND constraint_type = 'CHECK'
);
SET @generation_task_epoch_check_sql = IF(
    @generation_task_epoch_check_exists = 0,
    'ALTER TABLE generation_task ADD CONSTRAINT chk_generation_task_execution_epoch CHECK (executionEpoch >= 0)',
    'SELECT 1'
);
PREPARE generation_task_epoch_check_statement FROM @generation_task_epoch_check_sql;
EXECUTE generation_task_epoch_check_statement;
DEALLOCATE PREPARE generation_task_epoch_check_statement;

SET @app_generation_epoch_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'app'
      AND constraint_name = 'chk_app_generation_execution_epoch'
      AND constraint_type = 'CHECK'
);
SET @app_generation_epoch_check_sql = IF(
    @app_generation_epoch_check_exists = 0,
    'ALTER TABLE app ADD CONSTRAINT chk_app_generation_execution_epoch CHECK ((isGenerating = 0 AND generationExecutionEpoch IS NULL) OR (isGenerating = 1 AND generationExecutionEpoch IS NOT NULL AND generationExecutionEpoch >= 0))',
    'SELECT 1'
);
PREPARE app_generation_epoch_check_statement FROM @app_generation_epoch_check_sql;
EXECUTE app_generation_epoch_check_statement;
DEALLOCATE PREPARE app_generation_epoch_check_statement;

SET @checkpoint_execution_epoch_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_orchestration_checkpoint'
      AND column_name = 'executionEpoch'
);
SET @checkpoint_execution_epoch_sql = IF(
    @checkpoint_execution_epoch_exists = 0,
    'ALTER TABLE generation_orchestration_checkpoint ADD COLUMN executionEpoch bigint default 0 not null comment ''generation worker fencing epoch'' AFTER appId',
    'SELECT 1'
);
PREPARE checkpoint_execution_epoch_statement FROM @checkpoint_execution_epoch_sql;
EXECUTE checkpoint_execution_epoch_statement;
DEALLOCATE PREPARE checkpoint_execution_epoch_statement;

UPDATE generation_orchestration_checkpoint checkpoint_state
LEFT JOIN generation_task task
       ON task.taskId = checkpoint_state.taskId
      AND task.isDelete = 0
SET checkpoint_state.executionEpoch = GREATEST(
        COALESCE(checkpoint_state.executionEpoch, 0),
        COALESCE(task.executionEpoch, 0)
    )
WHERE checkpoint_state.isDelete = 0;

SET @checkpoint_epoch_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'generation_orchestration_checkpoint'
      AND constraint_name = 'chk_generation_orchestration_execution_epoch'
      AND constraint_type = 'CHECK'
);
SET @checkpoint_epoch_check_sql = IF(
    @checkpoint_epoch_check_exists = 0,
    'ALTER TABLE generation_orchestration_checkpoint ADD CONSTRAINT chk_generation_orchestration_execution_epoch CHECK (executionEpoch >= 0)',
    'SELECT 1'
);
PREPARE checkpoint_epoch_check_statement FROM @checkpoint_epoch_check_sql;
EXECUTE checkpoint_epoch_check_statement;
DEALLOCATE PREPARE checkpoint_epoch_check_statement;
