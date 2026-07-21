-- Allow an ordered pool of enabled models per type so the runtime can fail over by sortOrder.
ALTER TABLE ai_model
    DROP INDEX uk_active_model_type,
    DROP COLUMN activeModelType;

CREATE INDEX idx_ai_model_runtime_pool
    ON ai_model (modelType, isEnabled, isDelete, sortOrder, id);
