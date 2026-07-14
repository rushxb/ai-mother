-- 应用 Database 资源生产级完整性迁移（MySQL 8.x）。
-- 发布既有数据库时执行一次：统一 SQLite 拼写、修正默认值，并补齐并发幂等启用所需唯一索引。

UPDATE app_database_resource
SET dbEngine = 'SQLite'
WHERE LOWER(REPLACE(dbEngine, ' ', '')) IN ('sqllite', 'sqlite');

ALTER TABLE app_database_resource
    MODIFY COLUMN dbEngine varchar(64) default 'SQLite' not null comment '数据库引擎';

SET @app_database_resource_app_id_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'app_database_resource'
      AND index_name = 'uk_appId'
);
SET @app_database_resource_app_id_index_sql = IF(
    @app_database_resource_app_id_index_exists = 0,
    'ALTER TABLE app_database_resource ADD UNIQUE KEY uk_appId (appId)',
    'SELECT 1'
);
PREPARE app_database_resource_app_id_index_statement
    FROM @app_database_resource_app_id_index_sql;
EXECUTE app_database_resource_app_id_index_statement;
DEALLOCATE PREPARE app_database_resource_app_id_index_statement;

SET @app_database_resource_resource_id_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'app_database_resource'
      AND index_name = 'uk_resourceId'
);
SET @app_database_resource_resource_id_index_sql = IF(
    @app_database_resource_resource_id_index_exists = 0,
    'ALTER TABLE app_database_resource ADD UNIQUE KEY uk_resourceId (resourceId)',
    'SELECT 1'
);
PREPARE app_database_resource_resource_id_index_statement
    FROM @app_database_resource_resource_id_index_sql;
EXECUTE app_database_resource_resource_id_index_statement;
DEALLOCATE PREPARE app_database_resource_resource_id_index_statement;
