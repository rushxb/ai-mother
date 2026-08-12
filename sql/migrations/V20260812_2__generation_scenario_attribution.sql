SET @generation_scenario_columns_exist = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND column_name = 'intentSignature'
);
SET @generation_scenario_columns_sql = IF(
    @generation_scenario_columns_exist = 0,
    'ALTER TABLE generation_task
        ADD COLUMN intentSignature char(64) NULL COMMENT ''结构化意图场景签名'' AFTER route,
        ADD COLUMN intentProfileVersion varchar(32) NULL COMMENT ''意图画像协议版本'' AFTER intentSignature,
        ADD COLUMN routeDecisionVersion varchar(32) NULL COMMENT ''路由决策协议版本'' AFTER intentProfileVersion,
        ADD COLUMN routeEvidenceJson text NULL COMMENT ''路由证据 JSON'' AFTER routeDecisionVersion,
        ADD COLUMN routeAlternativesJson text NULL COMMENT ''备选路由 JSON'' AFTER routeEvidenceJson,
        ADD COLUMN routeReleaseIdentity varchar(64) NULL COMMENT ''路由发布身份'' AFTER routeAlternativesJson',
    'SELECT 1'
);
PREPARE generation_scenario_columns_statement FROM @generation_scenario_columns_sql;
EXECUTE generation_scenario_columns_statement;
DEALLOCATE PREPARE generation_scenario_columns_statement;

SET @generation_scenario_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_generation_task_scenario_attribution'
);
SET @generation_scenario_index_sql = IF(
    @generation_scenario_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_generation_task_scenario_attribution
        (intentSignature, endTime, route, status, id)',
    'SELECT 1'
);
PREPARE generation_scenario_index_statement FROM @generation_scenario_index_sql;
EXECUTE generation_scenario_index_statement;
DEALLOCATE PREPARE generation_scenario_index_statement;
