-- 用户积分账务完整性迁移（MySQL 8.x）。
-- 发布既有数据库时执行一次：补齐积分余额、流水表、幂等索引和账务 CHECK 约束。
-- 本迁移不会修正或删除异常账务数据；若既有数据违反约束，应先人工核对并修复后重试。

SET @user_credit_balance_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND column_name = 'creditBalance'
);
SET @user_credit_balance_sql = IF(
    @user_credit_balance_exists = 0,
    'ALTER TABLE `user` ADD COLUMN creditBalance bigint default 0 not null comment ''用户积分余额'' AFTER userRole',
    'SELECT 1'
);
PREPARE user_credit_balance_statement FROM @user_credit_balance_sql;
EXECUTE user_credit_balance_statement;
DEALLOCATE PREPARE user_credit_balance_statement;

ALTER TABLE `user`
    MODIFY COLUMN creditBalance bigint default 0 not null comment '用户积分余额';

SET @user_credit_balance_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'user'
      AND constraint_name = 'chk_user_credit_balance_nonnegative'
      AND constraint_type = 'CHECK'
);
SET @user_credit_balance_check_sql = IF(
    @user_credit_balance_check_exists = 0,
    'ALTER TABLE `user` ADD CONSTRAINT chk_user_credit_balance_nonnegative CHECK (creditBalance >= 0)',
    'SELECT 1'
);
PREPARE user_credit_balance_check_statement FROM @user_credit_balance_check_sql;
EXECUTE user_credit_balance_check_statement;
DEALLOCATE PREPARE user_credit_balance_check_statement;

CREATE TABLE IF NOT EXISTS user_credit_transaction
(
    id            bigint auto_increment comment 'id' primary key,
    userId        bigint                             not null comment '用户id',
    changeAmount  bigint                             not null comment '积分变动，正数增加，负数扣除',
    balanceAfter  bigint                             not null comment '变动后余额',
    type          varchar(64)                        not null comment '变动类型：ACCOUNT_INITIALIZATION/ADMIN_ADJUST/GENERATION_CHARGE',
    bizId         varchar(128)                       not null comment '业务id，例如 generation taskId',
    remark        varchar(512)                       not null comment '备注',
    adminUserId   bigint                             null comment '管理员操作人id',
    tokenCount    bigint                             null comment '本次 token 数',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete      tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_type_bizId (type, bizId),
    INDEX idx_userId_createTime (userId, createTime),
    INDEX idx_adminUserId_createTime (adminUserId, createTime),
    CONSTRAINT chk_user_credit_transaction_balance_nonnegative CHECK (balanceAfter >= 0),
    CONSTRAINT chk_user_credit_transaction_shape CHECK (
        (type = 'ACCOUNT_INITIALIZATION' AND changeAmount > 0
            AND adminUserId IS NOT NULL AND tokenCount IS NULL)
        OR (type = 'ADMIN_ADJUST' AND changeAmount <> 0
            AND adminUserId IS NOT NULL AND tokenCount IS NULL)
        OR (type = 'GENERATION_CHARGE' AND changeAmount <= 0
            AND adminUserId IS NULL AND tokenCount IS NOT NULL AND tokenCount >= 0)
    )
) comment '用户积分流水' collate = utf8mb4_unicode_ci;

ALTER TABLE user_credit_transaction
    MODIFY COLUMN userId bigint not null comment '用户id',
    MODIFY COLUMN changeAmount bigint not null comment '积分变动，正数增加，负数扣除',
    MODIFY COLUMN balanceAfter bigint not null comment '变动后余额',
    MODIFY COLUMN type varchar(64) not null comment '变动类型：ACCOUNT_INITIALIZATION/ADMIN_ADJUST/GENERATION_CHARGE',
    MODIFY COLUMN bizId varchar(128) not null comment '业务id，例如 generation taskId',
    MODIFY COLUMN remark varchar(512) not null comment '备注',
    MODIFY COLUMN tokenCount bigint null comment '本次 token 数';

SET @user_credit_unique_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'user_credit_transaction'
      AND index_name = 'uk_type_bizId'
);
SET @user_credit_unique_index_sql = IF(
    @user_credit_unique_index_exists = 0,
    'ALTER TABLE user_credit_transaction ADD UNIQUE KEY uk_type_bizId (type, bizId)',
    'SELECT 1'
);
PREPARE user_credit_unique_index_statement FROM @user_credit_unique_index_sql;
EXECUTE user_credit_unique_index_statement;
DEALLOCATE PREPARE user_credit_unique_index_statement;

SET @user_credit_balance_after_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'user_credit_transaction'
      AND constraint_name = 'chk_user_credit_transaction_balance_nonnegative'
      AND constraint_type = 'CHECK'
);
SET @user_credit_balance_after_check_sql = IF(
    @user_credit_balance_after_check_exists = 0,
    'ALTER TABLE user_credit_transaction ADD CONSTRAINT chk_user_credit_transaction_balance_nonnegative CHECK (balanceAfter >= 0)',
    'SELECT 1'
);
PREPARE user_credit_balance_after_check_statement FROM @user_credit_balance_after_check_sql;
EXECUTE user_credit_balance_after_check_statement;
DEALLOCATE PREPARE user_credit_balance_after_check_statement;

SET @user_credit_shape_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'user_credit_transaction'
      AND constraint_name = 'chk_user_credit_transaction_shape'
      AND constraint_type = 'CHECK'
);
SET @user_credit_shape_check_sql = IF(
    @user_credit_shape_check_exists = 0,
    'ALTER TABLE user_credit_transaction ADD CONSTRAINT chk_user_credit_transaction_shape CHECK ((type = ''ACCOUNT_INITIALIZATION'' AND changeAmount > 0 AND adminUserId IS NOT NULL AND tokenCount IS NULL) OR (type = ''ADMIN_ADJUST'' AND changeAmount <> 0 AND adminUserId IS NOT NULL AND tokenCount IS NULL) OR (type = ''GENERATION_CHARGE'' AND changeAmount <= 0 AND adminUserId IS NULL AND tokenCount IS NOT NULL AND tokenCount >= 0))',
    'SELECT 1'
);
PREPARE user_credit_shape_check_statement FROM @user_credit_shape_check_sql;
EXECUTE user_credit_shape_check_statement;
DEALLOCATE PREPARE user_credit_shape_check_statement;
