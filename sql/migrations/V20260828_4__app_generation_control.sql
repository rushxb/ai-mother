-- 应用预算必须在模型预检预授权阶段即可归属，不能依赖尚未创建的 generation_task。
ALTER TABLE user_credit_transaction
    ADD COLUMN appId bigint NULL COMMENT '生成任务所属应用；非生成和无法归属的历史流水为空' AFTER tenantId,
    ADD INDEX idx_app_generation_budget (appId, type, isDelete, createTime, bizId);

-- 尽可能补齐已有正式任务流水；没有形成任务的历史预检流水保持 NULL，不猜测归属。
UPDATE user_credit_transaction ledger
JOIN generation_task task
  ON task.taskId = ledger.bizId
 AND task.isDelete = 0
SET ledger.appId = task.appId
WHERE ledger.type IN ('GENERATION_CHARGE', 'GENERATION_RESERVATION', 'GENERATION_SETTLEMENT')
  AND ledger.appId IS NULL
  AND ledger.isDelete = 0;

CREATE TABLE app_generation_control
(
    appId                       bigint       NOT NULL COMMENT '应用 id',
    generationPaused            tinyint      NOT NULL DEFAULT 0 COMMENT '是否暂停接收新生成任务',
    emergencyStopped            tinyint      NOT NULL DEFAULT 0 COMMENT '是否紧急停止生成并取消活动任务',
    maxConcurrentTasks          int          NOT NULL DEFAULT 1 COMMENT '应用非终态任务上限；当前安全上限为 1',
    modelPolicy                 varchar(32)  NOT NULL DEFAULT 'PLATFORM_DEFAULT' COMMENT '模型策略',
    dependencyMutationPolicy    varchar(32)  NOT NULL DEFAULT 'ALLOW' COMMENT '依赖清单修改策略',
    dependencyNetworkPolicy     varchar(32)  NOT NULL DEFAULT 'TRUSTED_REGISTRY_ONLY' COMMENT '依赖网络策略',
    dangerousToolPolicy         varchar(32)  NOT NULL DEFAULT 'REQUIRE_APPROVAL' COMMENT '危险工具策略',
    monthlyCreditLimit          bigint       NULL COMMENT '应用月积分上限；NULL 表示继承租户上限',
    version                     bigint       NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    updatedBy                   bigint       NOT NULL COMMENT '最后更新人',
    createTime                  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime                  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (appId),
    CONSTRAINT chk_app_generation_control_flags
        CHECK (generationPaused IN (0, 1) AND emergencyStopped IN (0, 1)),
    CONSTRAINT chk_app_generation_control_concurrency
        CHECK (maxConcurrentTasks = 1),
    CONSTRAINT chk_app_generation_control_model
        CHECK (modelPolicy IN ('PLATFORM_DEFAULT', 'ECONOMY_ONLY')),
    CONSTRAINT chk_app_generation_control_dependency_mutation
        CHECK (dependencyMutationPolicy IN ('ALLOW', 'DENY')),
    CONSTRAINT chk_app_generation_control_dependency_network
        CHECK (dependencyNetworkPolicy IN ('TRUSTED_REGISTRY_ONLY', 'DENY')),
    CONSTRAINT chk_app_generation_control_dangerous_tool
        CHECK (dangerousToolPolicy IN ('REQUIRE_APPROVAL', 'DENY')),
    CONSTRAINT chk_app_generation_control_budget
        CHECK (monthlyCreditLimit IS NULL OR monthlyCreditLimit >= 0),
    CONSTRAINT chk_app_generation_control_version
        CHECK (version > 0),
    CONSTRAINT chk_app_generation_control_operator
        CHECK (updatedBy > 0)
) COMMENT '应用级生成控制策略' COLLATE = utf8mb4_unicode_ci;
