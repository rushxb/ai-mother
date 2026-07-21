-- Production credit authorization lifecycle for generation tasks:
-- reserve before durable submission, then capture or release from measured token usage.
ALTER TABLE user_credit_transaction
    DROP CHECK chk_user_credit_transaction_shape;

ALTER TABLE user_credit_transaction
    ADD CONSTRAINT chk_user_credit_transaction_shape CHECK (
        (type = 'ACCOUNT_INITIALIZATION' AND changeAmount > 0
            AND adminUserId IS NOT NULL AND tokenCount IS NULL)
        OR (type = 'ADMIN_ADJUST' AND changeAmount <> 0
            AND adminUserId IS NOT NULL AND tokenCount IS NULL)
        OR (type = 'GENERATION_CHARGE' AND changeAmount <= 0
            AND adminUserId IS NULL AND tokenCount IS NOT NULL AND tokenCount >= 0)
        OR (type = 'GENERATION_RESERVATION' AND changeAmount < 0
            AND adminUserId IS NULL AND tokenCount IS NULL)
        OR (type = 'GENERATION_SETTLEMENT'
            AND adminUserId IS NULL AND tokenCount IS NOT NULL AND tokenCount >= 0)
    );

SET @user_runtime_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_user_runtime_status'
);
SET @user_runtime_index_sql = IF(
    @user_runtime_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_user_runtime_status (userId, status, isDelete, submittedAt)',
    'SELECT 1'
);
PREPARE user_runtime_index_statement FROM @user_runtime_index_sql;
EXECUTE user_runtime_index_statement;
DEALLOCATE PREPARE user_runtime_index_statement;
