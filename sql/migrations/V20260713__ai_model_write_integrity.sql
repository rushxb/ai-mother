-- AI 模型管理写入完整性迁移（MySQL 8.0+，仅执行一次）
-- 1. 同一模型类型只允许一个未删除且已启用的模型；
-- 2. 将历史重复启用数据按 sortOrder、updateTime、id 确定性收敛；
-- 3. API Key 列长度与后端请求契约保持一致。

START TRANSACTION;

UPDATE ai_model AS target
JOIN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY modelType
                   ORDER BY sortOrder ASC, updateTime DESC, id DESC
               ) AS row_number_in_type
        FROM ai_model
        WHERE isEnabled = 1
          AND isDelete = 0
    ) AS ranked_models
    WHERE row_number_in_type > 1
) AS duplicate_enabled_models ON duplicate_enabled_models.id = target.id
SET target.isEnabled = 0;

COMMIT;

ALTER TABLE ai_model
    MODIFY COLUMN apiKey varchar(2048) null comment 'API 密钥',
    ADD COLUMN activeModelType varchar(32) generated always as
        (case when isEnabled = 1 and isDelete = 0 then modelType else null end) stored
        comment '启用模型类型并发唯一键',
    ADD UNIQUE KEY uk_active_model_type (activeModelType),
    ADD INDEX idx_modelType_enabled (modelType, isEnabled, isDelete);
