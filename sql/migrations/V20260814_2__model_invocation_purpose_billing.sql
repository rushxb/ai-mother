-- 将 generation_model_call 扩展为所有物理模型调用共用的账本。
-- 外围调用没有应用 ID，但必须有稳定 purpose 与明确的免费/豁免事实。
ALTER TABLE generation_model_call
    MODIFY COLUMN appId bigint NULL comment '应用id；外围模型调用允许为空',
    ADD COLUMN invocationPurpose varchar(32) default 'GENERATION' not null
        comment '稳定调用目的' AFTER userId,
    ADD COLUMN billingMode varchar(16) default 'BILLABLE' not null
        comment 'BILLABLE/EXEMPT' AFTER invocationPurpose,
    ADD COLUMN billingExemptionReason varchar(64) null
        comment 'EXEMPT 的有界审计原因' AFTER billingMode,
    ADD CONSTRAINT chk_generation_model_call_purpose CHECK (
        invocationPurpose IN (
            'GENERATION', 'PROMPT_OPTIMIZATION', 'APP_NAME_ENRICHMENT', 'CONNECTION_TEST')),
    ADD CONSTRAINT chk_generation_model_call_billing CHECK (
        (billingMode = 'BILLABLE' AND billingExemptionReason IS NULL)
        OR (billingMode = 'EXEMPT' AND billingExemptionReason IS NOT NULL)),
    DROP INDEX idx_generation_model_call_outcome,
    ADD INDEX idx_model_invocation_recovery
        (invocationPurpose, billingMode, callStatus, createTime);
