-- 终态意图版本 2 冻结低敏交付回执；滚动发布期间继续允许版本 1 待处理记录。
-- V20260812_1 已在所有受支持迁移路径上创建同名约束，因此这里确定性替换。
ALTER TABLE generation_task
    DROP CHECK chk_generation_task_terminal_intent;

ALTER TABLE generation_task
    ADD CONSTRAINT chk_generation_task_terminal_intent CHECK (
        (terminalIntentSchemaVersion IS NULL
            AND terminalIntentPayloadJson IS NULL
            AND terminalIntentExecutionEpoch IS NULL
            AND terminalIntentPreparedAt IS NULL
            AND terminalIntentFinalizedAt IS NULL)
        OR (terminalIntentSchemaVersion BETWEEN 1 AND 2
            AND terminalIntentPayloadJson IS NOT NULL
            AND terminalIntentExecutionEpoch IS NOT NULL
            AND terminalIntentExecutionEpoch > 0
            AND terminalIntentPreparedAt IS NOT NULL
            AND (terminalIntentFinalizedAt IS NULL
                OR terminalIntentFinalizedAt >= terminalIntentPreparedAt))
    );
