-- 发布恢复终态意图与单行终态副作用 outbox。
SET @generation_terminal_intent_columns_exist = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'terminalIntentSchemaVersion'
);
SET @generation_terminal_intent_columns_sql = IF(
    @generation_terminal_intent_columns_exist = 0,
    'ALTER TABLE generation_task
        ADD COLUMN terminalIntentSchemaVersion int NULL COMMENT ''发布恢复终态意图协议版本'' AFTER publicationCommittedAt,
        ADD COLUMN terminalIntentPayloadJson mediumtext NULL COMMENT ''发布前冻结的完整终态命令'' AFTER terminalIntentSchemaVersion,
        ADD COLUMN terminalIntentExecutionEpoch bigint NULL COMMENT ''终态意图所属执行轮次'' AFTER terminalIntentPayloadJson,
        ADD COLUMN terminalIntentPreparedAt datetime(6) NULL COMMENT ''终态意图冻结时间'' AFTER terminalIntentExecutionEpoch,
        ADD COLUMN terminalIntentFinalizedAt datetime(6) NULL COMMENT ''终态意图完成提交时间'' AFTER terminalIntentPreparedAt,
        ADD COLUMN terminalEffectsAttempts int DEFAULT 0 NOT NULL COMMENT ''终态副作用处理次数'' AFTER terminalIntentFinalizedAt,
        ADD COLUMN terminalEffectsError varchar(1024) NULL COMMENT ''终态副作用最近错误'' AFTER terminalEffectsAttempts,
        ADD COLUMN terminalEffectsNextAttemptAt datetime(6) NULL COMMENT ''终态副作用下次处理时间'' AFTER terminalEffectsError,
        ADD COLUMN terminalEffectsLeaseOwner varchar(128) NULL COMMENT ''终态副作用租约所有者'' AFTER terminalEffectsNextAttemptAt,
        ADD COLUMN terminalEffectsLeaseUntil datetime(6) NULL COMMENT ''终态副作用租约截止时间'' AFTER terminalEffectsLeaseOwner,
        ADD COLUMN terminalEffectsCompletedAt datetime(6) NULL COMMENT ''终态副作用完成时间'' AFTER terminalEffectsLeaseUntil',
    'SELECT 1'
);
PREPARE generation_terminal_intent_columns_statement
    FROM @generation_terminal_intent_columns_sql;
EXECUTE generation_terminal_intent_columns_statement;
DEALLOCATE PREPARE generation_terminal_intent_columns_statement;

SET @generation_terminal_effects_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_generation_task_terminal_effects'
);
SET @generation_terminal_effects_index_sql = IF(
    @generation_terminal_effects_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_generation_task_terminal_effects
        (terminalEffectsCompletedAt, terminalEffectsNextAttemptAt, terminalEffectsLeaseUntil, id)',
    'SELECT 1'
);
PREPARE generation_terminal_effects_index_statement
    FROM @generation_terminal_effects_index_sql;
EXECUTE generation_terminal_effects_index_statement;
DEALLOCATE PREPARE generation_terminal_effects_index_statement;

SET @generation_terminal_effects_attempts_check_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND constraint_name = 'chk_generation_task_terminal_effects_attempts'
);
SET @generation_terminal_effects_attempts_check_sql = IF(
    @generation_terminal_effects_attempts_check_exists = 0,
    'ALTER TABLE generation_task ADD CONSTRAINT chk_generation_task_terminal_effects_attempts
        CHECK (terminalEffectsAttempts >= 0)',
    'SELECT 1'
);
PREPARE generation_terminal_effects_attempts_check_statement
    FROM @generation_terminal_effects_attempts_check_sql;
EXECUTE generation_terminal_effects_attempts_check_statement;
DEALLOCATE PREPARE generation_terminal_effects_attempts_check_statement;

SET @generation_terminal_intent_check_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND constraint_name = 'chk_generation_task_terminal_intent'
);
SET @generation_terminal_intent_check_sql = IF(
    @generation_terminal_intent_check_exists = 0,
    'ALTER TABLE generation_task ADD CONSTRAINT chk_generation_task_terminal_intent CHECK (
        (terminalIntentSchemaVersion IS NULL
            AND terminalIntentPayloadJson IS NULL
            AND terminalIntentExecutionEpoch IS NULL
            AND terminalIntentPreparedAt IS NULL
            AND terminalIntentFinalizedAt IS NULL)
        OR (terminalIntentSchemaVersion = 1
            AND terminalIntentPayloadJson IS NOT NULL
            AND terminalIntentExecutionEpoch IS NOT NULL
            AND terminalIntentExecutionEpoch > 0
            AND terminalIntentPreparedAt IS NOT NULL
            AND (terminalIntentFinalizedAt IS NULL
                OR terminalIntentFinalizedAt >= terminalIntentPreparedAt))
    )',
    'SELECT 1'
);
PREPARE generation_terminal_intent_check_statement
    FROM @generation_terminal_intent_check_sql;
EXECUTE generation_terminal_intent_check_statement;
DEALLOCATE PREPARE generation_terminal_intent_check_statement;
