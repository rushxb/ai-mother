-- Establish the tenant as the authorization and data-isolation boundary.
CREATE TABLE IF NOT EXISTS tenant
(
    id          bigint auto_increment primary key,
    tenantKey   varchar(191)                              not null,
    tenantType  varchar(32)                               not null,
    displayName varchar(128)                              not null,
    ownerUserId bigint                                    not null,
    status      varchar(32) default 'active'              not null,
    createTime  datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime  datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    isDelete    tinyint     default 0                    not null,
    UNIQUE KEY uk_tenant_key (tenantKey),
    INDEX idx_tenant_owner_type (ownerUserId, tenantType, isDelete),
    INDEX idx_tenant_status (status, isDelete, id),
    CONSTRAINT fk_tenant_owner_user FOREIGN KEY (ownerUserId) REFERENCES `user` (id),
    CONSTRAINT chk_tenant_type CHECK (tenantType IN ('personal', 'organization')),
    CONSTRAINT chk_tenant_status CHECK (status IN ('active', 'suspended')),
    CONSTRAINT chk_tenant_owner CHECK (ownerUserId > 0)
) comment 'tenant authorization and data isolation boundary' collate = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tenant_membership
(
    id         bigint auto_increment primary key,
    tenantId   bigint                                    not null,
    userId     bigint                                    not null,
    role       varchar(32)                               not null,
    status     varchar(32) default 'active'              not null,
    joinedAt   datetime(6)                               not null,
    createTime datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    isDelete   tinyint     default 0                    not null,
    UNIQUE KEY uk_tenant_membership_identity (tenantId, userId),
    INDEX idx_tenant_membership_user (userId, status, isDelete, tenantId),
    INDEX idx_tenant_membership_tenant (tenantId, status, role, isDelete),
    CONSTRAINT fk_tenant_membership_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id),
    CONSTRAINT fk_tenant_membership_user FOREIGN KEY (userId) REFERENCES `user` (id),
    CONSTRAINT chk_tenant_membership_role CHECK (role IN ('viewer', 'developer', 'admin', 'owner')),
    CONSTRAINT chk_tenant_membership_status CHECK (status IN ('invited', 'active', 'suspended')),
    CONSTRAINT chk_tenant_membership_identity CHECK (tenantId > 0 AND userId > 0)
) comment 'tenant membership and role assignment' collate = utf8mb4_unicode_ci;

-- Include logically deleted users: their historical applications still require a valid tenant.
INSERT INTO tenant (
    tenantKey, tenantType, displayName, ownerUserId, status,
    createTime, updateTime, isDelete
)
SELECT CONCAT('personal:', u.id),
       'personal',
       LEFT(COALESCE(NULLIF(TRIM(u.userName), ''),
                     NULLIF(TRIM(u.userAccount), ''),
                     CONCAT('Personal workspace ', u.id)), 128),
       u.id,
       'active',
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       0
FROM `user` u
ON DUPLICATE KEY UPDATE
    ownerUserId = VALUES(ownerUserId),
    tenantType = 'personal',
    status = 'active',
    updateTime = CURRENT_TIMESTAMP(6),
    isDelete = 0;

INSERT INTO tenant_membership (
    tenantId, userId, role, status, joinedAt,
    createTime, updateTime, isDelete
)
SELECT t.id,
       u.id,
       'owner',
       'active',
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       0
FROM `user` u
JOIN tenant t
  ON t.tenantKey = CONCAT('personal:', u.id)
 AND t.tenantType = 'personal'
ON DUPLICATE KEY UPDATE
    role = 'owner',
    status = 'active',
    updateTime = CURRENT_TIMESTAMP(6),
    isDelete = 0;

SET @app_tenant_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app'
      AND column_name = 'tenantId'
);
SET @app_tenant_column_sql = IF(
    @app_tenant_column_exists = 0,
    'ALTER TABLE app ADD COLUMN tenantId bigint NULL AFTER userId',
    'SELECT 1'
);
PREPARE app_tenant_column_statement FROM @app_tenant_column_sql;
EXECUTE app_tenant_column_statement;
DEALLOCATE PREPARE app_tenant_column_statement;

UPDATE app a
JOIN tenant t
  ON t.tenantKey = CONCAT('personal:', a.userId)
 AND t.tenantType = 'personal'
SET a.tenantId = t.id
WHERE a.tenantId IS NULL;

-- This conversion intentionally fails when orphan applications cannot be backfilled.
SET @app_tenant_nullable = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'app'
      AND column_name = 'tenantId'
      AND is_nullable = 'YES'
);
SET @app_tenant_not_null_sql = IF(
    @app_tenant_nullable > 0,
    'ALTER TABLE app MODIFY COLUMN tenantId bigint NOT NULL',
    'SELECT 1'
);
PREPARE app_tenant_not_null_statement FROM @app_tenant_not_null_sql;
EXECUTE app_tenant_not_null_statement;
DEALLOCATE PREPARE app_tenant_not_null_statement;

SET @app_tenant_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'app'
      AND index_name = 'idx_app_tenant_cursor'
);
SET @app_tenant_index_sql = IF(
    @app_tenant_index_exists = 0,
    'ALTER TABLE app ADD INDEX idx_app_tenant_cursor (tenantId, isDelete, createTime, id)',
    'SELECT 1'
);
PREPARE app_tenant_index_statement FROM @app_tenant_index_sql;
EXECUTE app_tenant_index_statement;
DEALLOCATE PREPARE app_tenant_index_statement;

SET @app_tenant_fk_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'app'
      AND constraint_name = 'fk_app_tenant'
      AND constraint_type = 'FOREIGN KEY'
);
SET @app_tenant_fk_sql = IF(
    @app_tenant_fk_exists = 0,
    'ALTER TABLE app ADD CONSTRAINT fk_app_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id)',
    'SELECT 1'
);
PREPARE app_tenant_fk_statement FROM @app_tenant_fk_sql;
EXECUTE app_tenant_fk_statement;
DEALLOCATE PREPARE app_tenant_fk_statement;
