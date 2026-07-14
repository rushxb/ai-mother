-- AI 模型软删除身份唯一约束迁移（MySQL 8.0+，仅执行一次）
-- 仅限制未删除记录的 provider/modelId 唯一，允许删除后重新创建同一模型。

ALTER TABLE ai_model
    DROP INDEX uk_provider_modelId,
    ADD COLUMN activeProvider varchar(64) generated always as
        (case when isDelete = 0 then provider else null end) stored
        comment '未删除模型提供商唯一键',
    ADD COLUMN activeModelId varchar(128) generated always as
        (case when isDelete = 0 then modelId else null end) stored
        comment '未删除模型标识唯一键',
    ADD UNIQUE KEY uk_active_provider_model (activeProvider, activeModelId);
