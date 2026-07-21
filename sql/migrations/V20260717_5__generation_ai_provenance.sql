ALTER TABLE generation_model_call
    ADD COLUMN callStatus varchar(32) default 'SUCCESS' not null
        comment 'SUCCESS/ERROR' AFTER model,
    ADD COLUMN providerRequestId varchar(128) null
        comment 'provider response/request id' AFTER callStatus,
    ADD COLUMN errorCategory varchar(64) null
        comment 'bounded production error category' AFTER usageSource,
    ADD COLUMN requestHash char(64) null
        comment 'sha-256 of canonical rendered request' AFTER errorCategory,
    ADD COLUMN promptTemplateHash char(64) null
        comment 'sha-256 of rendered system prompt set' AFTER requestHash,
    ADD COLUMN toolSchemaHash char(64) null
        comment 'sha-256 of canonical tool schemas' AFTER promptTemplateHash,
    ADD COLUMN modelConfigHash char(64) null
        comment 'sha-256 of provider model request parameters' AFTER toolSchemaHash,
    ADD COLUMN requestMessageCount int default 0 not null
        comment 'number of messages sent to the provider' AFTER modelConfigHash,
    ADD COLUMN toolCount int default 0 not null
        comment 'number of exposed tool schemas' AFTER requestMessageCount,
    ADD CONSTRAINT chk_generation_model_call_status
        CHECK (callStatus IN ('SUCCESS', 'ERROR')),
    ADD CONSTRAINT chk_generation_model_call_counts
        CHECK (requestMessageCount >= 0 AND toolCount >= 0),
    ADD INDEX idx_generation_model_call_outcome (callStatus, createTime),
    ADD INDEX idx_generation_model_call_prompt_model
        (promptTemplateHash, model, callStatus, createTime);
