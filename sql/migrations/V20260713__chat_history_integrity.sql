-- 对话历史生产级完整性与查询索引治理（MySQL 8.x）。
-- 发布既有数据库时执行一次；执行前应确认旧索引名称与 create_table.sql 一致。

ALTER TABLE chat_history
    MODIFY COLUMN message MEDIUMTEXT NOT NULL COMMENT '消息',
    DROP INDEX idx_appId,
    DROP INDEX idx_createTime,
    DROP INDEX idx_appId_createTime,
    ADD INDEX idx_app_history_cursor (appId, isDelete, createTime, id),
    ADD INDEX idx_history_user_cursor (userId, isDelete, createTime, id),
    ADD INDEX idx_history_admin_cursor (isDelete, createTime, id);
