-- 为终态副作用 outbox 增加按操作的持久回执位标记。
SET @terminal_effect_receipt_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'terminalEffectsCompletedMask'
);
SET @terminal_effect_receipt_column_sql = IF(
    @terminal_effect_receipt_column_exists = 0,
    'ALTER TABLE generation_task
        ADD COLUMN terminalEffectsCompletedMask bigint DEFAULT 0 NOT NULL
            COMMENT ''已完成终态副作用位标记''
            AFTER terminalEffectsLeaseUntil',
    'SELECT 1'
);
PREPARE terminal_effect_receipt_column_statement
    FROM @terminal_effect_receipt_column_sql;
EXECUTE terminal_effect_receipt_column_statement;
DEALLOCATE PREPARE terminal_effect_receipt_column_statement;

SET @terminal_effect_receipt_check_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND constraint_name = 'chk_generation_task_terminal_effects_completed_mask'
);
SET @terminal_effect_receipt_check_sql = IF(
    @terminal_effect_receipt_check_exists = 0,
    'ALTER TABLE generation_task
        ADD CONSTRAINT chk_generation_task_terminal_effects_completed_mask
        CHECK (terminalEffectsCompletedMask >= 0)',
    'SELECT 1'
);
PREPARE terminal_effect_receipt_check_statement
    FROM @terminal_effect_receipt_check_sql;
EXECUTE terminal_effect_receipt_check_statement;
DEALLOCATE PREPARE terminal_effect_receipt_check_statement;
