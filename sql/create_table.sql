# 数据库初始化


-- 创建库
create database if not exists rush_ai_code_mother;

-- 切换库
use rush_ai_code_mother;

-- 用户表
-- 以下是建表语句

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    creditBalance bigint      default 0                 not null comment '用户积分余额',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName),
    CONSTRAINT chk_user_credit_balance_nonnegative CHECK (creditBalance >= 0)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 已有库升级可执行以下语句
alter table user add column if not exists creditBalance bigint default 0 not null comment '用户积分余额';

-- 租户授权与数据隔离边界
create table if not exists tenant
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
) comment '租户授权与数据隔离边界' collate = utf8mb4_unicode_ci;

create table if not exists tenant_membership
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
) comment '租户成员与角色分配' collate = utf8mb4_unicode_ci;

-- 用户积分流水表
create table if not exists user_credit_transaction
(
    id            bigint auto_increment comment 'id' primary key,
    userId        bigint                             not null comment '用户id',
    tenantId      bigint                             null comment '生成任务所属租户；非生成和历史流水为空',
    changeAmount  bigint                             not null comment '积分变动，正数增加，负数扣除',
    balanceAfter  bigint                             not null comment '变动后余额',
    type          varchar(64)                        not null comment '变动类型：初始化/调整/生成预授权/结算/兼容扣费',
    bizId         varchar(128)                       not null comment '业务id，例如 generation taskId',
    remark        varchar(512)                       not null comment '备注',
    adminUserId   bigint                             null comment '管理员操作人id',
    tokenCount    bigint                             null comment '本次 token 数',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete      tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_type_bizId (type, bizId),
    INDEX idx_userId_createTime (userId, createTime),
    INDEX idx_adminUserId_createTime (adminUserId, createTime),
    INDEX idx_tenant_generation_budget (tenantId, type, isDelete, createTime, bizId),
    CONSTRAINT chk_user_credit_transaction_balance_nonnegative CHECK (balanceAfter >= 0),
    CONSTRAINT chk_user_credit_transaction_shape CHECK (
        (type = 'ACCOUNT_INITIALIZATION' AND changeAmount > 0
            AND adminUserId IS NOT NULL AND tokenCount IS NULL)
        OR (type = 'ADMIN_ADJUST' AND changeAmount <> 0
            AND adminUserId IS NOT NULL AND tokenCount IS NULL)
        OR (type = 'GENERATION_CHARGE' AND changeAmount <= 0
            AND adminUserId IS NULL AND tokenCount IS NOT NULL AND tokenCount >= 0)
        OR (type = 'GENERATION_RESERVATION' AND changeAmount < 0
            AND adminUserId IS NULL AND tokenCount IS NULL)
        OR (type = 'GENERATION_SETTLEMENT'
            AND adminUserId IS NULL AND tokenCount IS NOT NULL AND tokenCount >= 0)
    )
) comment '用户积分流水' collate = utf8mb4_unicode_ci;

-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    isGenerating tinyint  default 0                 not null comment '是否正在生成',
    generatingMessage mediumtext                    null comment '当前生成中的 AI 响应快照',
    generatingStage varchar(64)                     null comment '当前生成阶段',
    generatingTaskId varchar(128)                    null comment '当前生成状态所有者任务 ID',
    generationExecutionEpoch bigint                  null comment '当前生成状态 fencing epoch',
    generationLeaseUntil datetime(6)                 null comment '生成状态租约到期时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    tenantId     bigint                             not null comment '租户授权边界',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId),           -- 提升基于用户 ID 的查询性能
    INDEX idx_app_tenant_cursor (tenantId, isDelete, createTime, id),
    INDEX idx_generation_lease (isGenerating, generationLeaseUntil),
    CONSTRAINT fk_app_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id),
    CONSTRAINT chk_app_generation_state_ownership CHECK (
        (isGenerating = 0 AND generatingTaskId IS NULL
            AND generationExecutionEpoch IS NULL AND generationLeaseUntil IS NULL)
        OR (isGenerating = 1 AND generatingTaskId IS NOT NULL
            AND generationExecutionEpoch IS NOT NULL AND generationExecutionEpoch >= 0
            AND generationLeaseUntil IS NOT NULL)
    )
) comment '应用' collate = utf8mb4_unicode_ci;

-- 已有库升级可执行以下语句
alter table app add column if not exists isGenerating tinyint default 0 not null comment '是否正在生成';
alter table app add column if not exists generatingMessage mediumtext null comment '当前生成中的 AI 响应快照';
alter table app add column if not exists generatingStage varchar(64) null comment '当前生成阶段';
alter table app add column if not exists generatingTaskId varchar(128) null comment '当前生成状态所有者任务 ID';
alter table app add column if not exists generationExecutionEpoch bigint null comment '当前生成状态 fencing epoch';
alter table app add column if not exists generationLeaseUntil datetime(6) null comment '生成状态租约到期时间';
alter table app add column if not exists devServerPort int null comment 'Vue 开发服务器端口号（预览用）';

-- 应用能力表：统一承载 database / analytics / git / mobile 等开关状态，避免 app 主表持续膨胀
create table if not exists app_capability
(
    id             bigint auto_increment comment 'id' primary key,
    appId          bigint                             not null comment '应用id',
    userId         bigint                             not null comment '创建用户id',
    capabilityType varchar(64)                        not null comment '能力类型：database/analytics/git/mobile',
    status         varchar(32) default 'enabled'      not null comment '状态：enabled/disabled/provisioning/error',
    configJson     text                               null comment '能力配置 JSON',
    editTime       datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_app_capability (appId, capabilityType),
    INDEX idx_userId_capabilityType (userId, capabilityType)
) comment '应用能力' collate = utf8mb4_unicode_ci;

-- 应用 Database 资源表：本期用于“启用 Database”，后续可扩展数据表、存储桶、认证和策略
create table if not exists app_database_resource
(
    id                 bigint auto_increment comment 'id' primary key,
    appId              bigint                             not null comment '应用id',
    userId             bigint                             not null comment '创建用户id',
    resourceId         varchar(128)                       not null comment 'Database 资源标识',
    resourceName       varchar(256)                       null comment 'Database 资源名称',
    databaseUrl        varchar(512)                       not null comment 'Database 访问 URL',
    dbEngine           varchar(64) default 'SQLite'       not null comment '数据库引擎',
    backendRuntime     varchar(64) default 'go'           not null comment '后端运行时',
    sqlExecutionPolicy varchar(64) default 'ask_every_time' not null comment 'SQL 执行策略：ask_every_time/always_allow',
    status             varchar(32) default 'active'       not null comment '状态：active/recycled/error',
    lastUsedTime       datetime                           null comment '最后使用时间',
    createTime         datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime         datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete           tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_appId (appId),
    UNIQUE KEY uk_resourceId (resourceId),
    INDEX idx_userId_status (userId, status),
    INDEX idx_lastUsedTime (lastUsedTime)
) comment '应用 Database 资源' collate = utf8mb4_unicode_ci;

-- 应用版本仓库表：为后续 git 托管版本号预留
create table if not exists app_git_repository
(
    id               bigint auto_increment comment 'id' primary key,
    appId            bigint                             not null comment '应用id',
    userId           bigint                             not null comment '创建用户id',
    provider         varchar(64) default 'internal_git' null comment 'Git 提供方',
    repositoryUrl    varchar(1024)                      null comment '仓库地址',
    defaultBranch    varchar(128) default 'main'        null comment '默认分支',
    latestCommitHash varchar(128)                       null comment '最新提交哈希',
    status           varchar(32) default 'active'       not null comment '状态',
    configJson       text                               null comment '扩展配置 JSON',
    createTime       datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_appId (appId),
    INDEX idx_userId_status (userId, status)
) comment '应用 Git 仓库' collate = utf8mb4_unicode_ci;

-- 应用运行渠道表：为 web / mobile / wechat 部署、二维码和微信打开方式预留
create table if not exists app_runtime_channel
(
    id             bigint auto_increment comment 'id' primary key,
    appId          bigint                             not null comment '应用id',
    userId         bigint                             not null comment '创建用户id',
    channelType    varchar(64)                        not null comment '渠道类型：web/mobile/wechat',
    deployUrl      varchar(1024)                      null comment '部署访问地址',
    qrCodeUrl      varchar(1024)                      null comment '移动端二维码地址',
    openMode       varchar(64)                        null comment '打开方式：browser/wechat',
    status         varchar(32) default 'active'       not null comment '状态',
    configJson     text                               null comment '渠道配置 JSON',
    lastDeployTime datetime                           null comment '最后部署时间',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_app_channel (appId, channelType),
    INDEX idx_userId_channelType (userId, channelType)
) comment '应用运行渠道' collate = utf8mb4_unicode_ci;

-- 应用数据分析配置表：为作品访问分析、事件分析和留存策略预留
create table if not exists app_analytics_config
(
    id             bigint auto_increment comment 'id' primary key,
    appId          bigint                             not null comment '应用id',
    userId         bigint                             not null comment '创建用户id',
    enabled        tinyint  default 0                 not null comment '是否启用数据分析',
    trackingKey    varchar(128)                       null comment '埋点追踪 key',
    retentionDays  int      default 30                not null comment '数据保留天数',
    configJson     text                               null comment '分析配置 JSON',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_appId (appId),
    INDEX idx_userId_enabled (userId, enabled)
) comment '应用数据分析配置' collate = utf8mb4_unicode_ci;

-- 对话历史表
create table chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     mediumtext                         not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_app_history_cursor (appId, isDelete, createTime, id),
    INDEX idx_history_user_cursor (userId, isDelete, createTime, id),
    INDEX idx_history_admin_cursor (isDelete, createTime, id)
) comment '对话历史' collate = utf8mb4_unicode_ci;

-- AI 生成任务主表：按 taskId 串联一次用户请求、AI 调用、工具执行、构建和自动修复
    create table if not exists generation_task
    (
        id                      bigint auto_increment comment 'id' primary key,
        taskId                  varchar(128)                       not null comment '生成任务 ID',
        appId                   bigint                             not null comment '应用id',
        userId                  bigint                             not null comment '创建用户id',
        tenantId                bigint                             not null comment '租户授权边界',
        idempotencyKeyHash      char(64) character set ascii collate ascii_bin null comment 'Idempotency-Key SHA-256',
        requestFingerprint      char(64) character set ascii collate ascii_bin null comment '提交请求规范指纹 SHA-256',
        originalCodeGenType     varchar(64)                        null comment '原始代码生成类型',
        targetCodeGenType       varchar(64)                        null comment '目标代码生成类型',
        status                  varchar(32) default 'queued'       not null comment '状态：queued/running/waiting_approval/success/failed/cancelled/deadline_exceeded',
        stage                   varchar(64)                        null comment '当前阶段',
        stageMessage            text                               null comment '当前阶段提示信息',
        userPrompt              mediumtext                         null comment '用户原始提示词',
        enhancedPrompt          mediumtext                         null comment '增强后的生成提示词',
        requiresBuildValidation tinyint     default 0              not null comment '是否需要构建校验',
        qualityGate             varchar(64)                        null comment '质量门禁级别',
        thinkingMode            varchar(16)                        null comment '实际使用的思考档位',
        changedFileCount        int                                null comment '有效变更文件数',
        firstBuildPassed        tinyint                            null comment '是否免修复通过构建',
        repairRounds            int                                null comment '实际修复轮次',
        firstPreviewMillis      bigint                             null comment '提交到可预览耗时毫秒',
        failureCategory         varchar(64)                        null comment '失败分类',
        reworkedAt              datetime(6)                        null comment '交付后被追加改修的时间',
        distilledAt             datetime(6)                        null comment '经验已蒸馏时间',
        orchestrationMode       varchar(64)                        null comment '编排模式',
        route                   varchar(64)                        null comment '运行时路由',
        intentSignature         char(64)                           null comment '结构化意图场景签名',
        intentProfileVersion    varchar(32)                        null comment '意图画像协议版本',
        routeDecisionVersion    varchar(32)                        null comment '路由决策协议版本',
        routeEvidenceJson       text                               null comment '路由证据 JSON',
        routeAlternativesJson   text                               null comment '备选路由 JSON',
        routeReleaseIdentity    varchar(64)                        null comment '路由发布身份',
        runtimeSchemaVersion    int                                null comment '可重建执行命令 schema 版本',
        runtimePayloadJson      mediumtext                         null comment '跨实例可重建执行命令 JSON',
        dispatchAt              datetime(6)                        null comment '最近一次进入 durable queue 的时间',
        dispatchAttempt         int         default 0              not null comment 'durable queue 投递尝试次数',
        dispatchError           varchar(1000)                      null comment '最近一次 durable queue 投递错误',
        submittedAt             datetime(6)                        not null comment '任务提交时间',
        deadlineAt              datetime(6)                        null comment '任务绝对截止时间',
        cancellationRequested   tinyint     default 0              not null comment '是否请求取消',
        cancellationReason      varchar(512)                       null comment '取消原因',
        leaseOwner              varchar(128)                       null comment 'worker 租约所有者',
        leaseUntil              datetime(6)                        null comment 'worker 租约到期时间',
        heartbeatAt             datetime(6)                        null comment 'worker 最近心跳时间',
        executionEpoch          bigint      default 0              not null comment '单调递增的 worker fencing epoch',
        attempt                 int         default 0              not null comment 'worker 领取次数',
        version                 bigint      default 0              not null comment '运行时乐观锁版本',
        startTime               datetime default CURRENT_TIMESTAMP not null comment '开始时间',
        endTime                 datetime                           null comment '结束时间',
        durationMs              bigint                             null comment '耗时毫秒',
        errorMessage            text                               null comment '错误信息',
        memorySummary           mediumtext                         null comment 'AI 可读的生成记忆摘要',
        memoryIndexedAt         datetime(6)                        null comment '语义记忆成功写入 Milvus 的时间',
        memoryIndexContractVersion int      default 0              not null comment '已写入的语义记忆完整契约版本；0 表示待索引',
        memoryIndexAttempts     int         default 0              not null comment '语义记忆索引尝试次数',
        memoryIndexError        varchar(1000)                      null comment '语义记忆最近索引错误',
        memoryIndexNextAttemptAt datetime(6)                       null comment '下一次语义记忆索引时间',
        memoryIndexLeaseOwner   varchar(128)                       null comment '语义记忆 outbox 租约持有者',
        memoryIndexLeaseUntil   datetime(6)                        null comment '语义记忆 outbox 租约到期时间',
        totalTokens             bigint   default 0                 not null comment '任务累计 token 数',
        creditCost              bigint   default 0                 not null comment '任务消耗积分',
        creditCharged           tinyint  default 0                 not null comment '是否已结算积分',
        createTime              datetime default CURRENT_TIMESTAMP not null comment '创建时间',
        updateTime              datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
        isDelete                tinyint  default 0                 not null comment '是否删除',
        UNIQUE KEY uk_taskId (taskId),
        UNIQUE KEY uk_generation_task_submission_idempotency
            (tenantId, userId, appId, idempotencyKeyHash),
        INDEX idx_appId_createTime (appId, createTime),
        INDEX idx_userId_createTime (userId, createTime),
        INDEX idx_status_createTime (status, createTime),
        INDEX idx_route_success_duration (route, status, isDelete, endTime, id),
        INDEX idx_runtime_lease (status, leaseUntil, isDelete),
        INDEX idx_runtime_dispatch (status, dispatchAt, leaseOwner, isDelete, submittedAt),
        INDEX idx_app_runtime_status (appId, status, submittedAt),
        INDEX idx_user_runtime_status (userId, status, isDelete, submittedAt),
        INDEX idx_memory_outbox (memoryIndexedAt, memoryIndexAttempts, status, isDelete, endTime),
        INDEX idx_memory_outbox_claim
            (memoryIndexedAt, memoryIndexNextAttemptAt, memoryIndexLeaseUntil,
             memoryIndexAttempts, status, isDelete, endTime),
        INDEX idx_memory_outbox_contract_claim
            (memoryIndexContractVersion, memoryIndexedAt, memoryIndexNextAttemptAt,
             memoryIndexLeaseUntil, memoryIndexAttempts, status, isDelete, endTime),
        INDEX idx_generation_task_tenant_runtime (tenantId, status, isDelete, submittedAt, id),
        INDEX idx_generation_task_distill_claim (distilledAt, status, isDelete, endTime, id),
        INDEX idx_generation_task_scenario_attribution (intentSignature, endTime, route, status, id),
        CONSTRAINT chk_generation_task_outcome_quality CHECK (
            (changedFileCount IS NULL OR changedFileCount >= 0)
                AND (repairRounds IS NULL OR repairRounds >= 0)
                AND (firstPreviewMillis IS NULL OR firstPreviewMillis >= 0)
                AND (firstBuildPassed IS NULL OR firstBuildPassed IN (0, 1))
        ),
        CONSTRAINT chk_generation_task_idempotency_pair CHECK (
            (idempotencyKeyHash IS NULL AND requestFingerprint IS NULL)
                OR (idempotencyKeyHash IS NOT NULL AND requestFingerprint IS NOT NULL)
        ),
        CONSTRAINT chk_generation_task_memory_contract_version CHECK (memoryIndexContractVersion >= 0),
        CONSTRAINT fk_generation_task_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id)
    ) comment 'AI 生成任务' collate = utf8mb4_unicode_ci;

create table if not exists semantic_memory_deletion_outbox
(
    id                bigint auto_increment primary key,
    operationId       char(64)                            not null,
    operationType     varchar(32)                         not null,
    tenantId          bigint                              not null,
    appId             bigint                              not null,
    requestedByUserId bigint                              not null,
    attempts          int         default 0               not null,
    nextAttemptAt     datetime(6)                         not null,
    leaseOwner        varchar(128)                        null,
    leaseUntil        datetime(6)                         null,
    lastError         varchar(1000)                       null,
    completedAt       datetime(6)                         null,
    createTime        datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime        datetime(6) default CURRENT_TIMESTAMP(6) not null
        on update CURRENT_TIMESTAMP(6),
    unique key uk_semantic_memory_deletion_operation (operationId),
    unique key uk_semantic_memory_deletion_scope (operationType, tenantId, appId),
    index idx_semantic_memory_deletion_claim
        (completedAt, nextAttemptAt, leaseUntil, id),
    constraint chk_semantic_memory_deletion_type
        check (operationType = 'DELETE_APPLICATION'),
    constraint chk_semantic_memory_deletion_identity
        check (tenantId > 0 and appId > 0 and requestedByUserId > 0),
    constraint chk_semantic_memory_deletion_attempts check (attempts >= 0),
    constraint chk_semantic_memory_deletion_lease check (
        (leaseOwner is null and leaseUntil is null)
        or (leaseOwner is not null and leaseUntil is not null)
    )
) comment '派生 Milvus 语义记忆删除 outbox' collate = utf8mb4_unicode_ci;

-- AI 破坏性工具一次性审批：MySQL 为事实源，支持重启恢复、原子决策和单次消费
-- Durable DAG checkpoints for cross-instance orchestration recovery.
create table if not exists generation_orchestration_checkpoint
(
    id                    bigint auto_increment primary key,
    taskId                varchar(128)                         not null comment 'generation task id',
    appId                 bigint                               not null comment 'application id',
    executionEpoch        bigint      default 0                not null comment 'generation worker fencing epoch',
    requestHash           char(64)                             not null comment 'sha-256 hash of the original request',
    status                varchar(32)                          not null comment 'running/completed/failed',
    runtimeState          varchar(32)                          not null comment 'agent runtime state',
    currentNode           varchar(128)                         null comment 'currently running DAG node',
    lastCompletedNode     varchar(128)                         null comment 'last completed DAG node',
    checkpointVersion     bigint      default 0                not null comment 'monotonic checkpoint version',
    payloadJson           mediumtext                           not null comment 'versioned orchestration checkpoint payload',
    payloadBytes          int                                  not null comment 'serialized payload bytes',
    createTime            datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime            datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    isDelete              tinyint     default 0                not null,
    UNIQUE KEY uk_generation_orchestration_task (taskId),
    INDEX idx_generation_orchestration_app (appId, isDelete, updateTime),
    INDEX idx_generation_orchestration_state (status, runtimeState, isDelete, updateTime),
    CONSTRAINT chk_generation_orchestration_checkpoint_version CHECK (checkpointVersion >= 0),
    CONSTRAINT chk_generation_orchestration_execution_epoch CHECK (executionEpoch >= 0),
    CONSTRAINT chk_generation_orchestration_payload_bytes CHECK (payloadBytes > 0)
) comment 'durable generation DAG checkpoint' collate = utf8mb4_unicode_ci;

create table if not exists generation_feedback
(
    id         bigint auto_increment comment 'id' primary key,
    taskId     varchar(128)                       not null comment '生成任务 ID',
    appId      bigint                             not null comment '应用 ID',
    userId     bigint                             not null comment '用户 ID',
    rating     tinyint                            not null comment '用户评分：1-5',
    outcome    varchar(64) default 'unspecified'  not null comment '反馈结果标签',
    comment    text                               null comment '用户反馈文本',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_generation_feedback_task_user (taskId, userId),
    KEY idx_generation_feedback_app_update (appId, updateTime),
    KEY idx_generation_feedback_rating_update (rating, updateTime),
    CONSTRAINT chk_generation_feedback_rating CHECK (rating between 1 and 5)
) comment 'AI 生成结果用户反馈' collate = utf8mb4_unicode_ci;

create table if not exists generation_tool_approval
(
    id          bigint auto_increment primary key,
    approvalId  char(64)                            not null comment '目标绑定审批 ID',
    taskId      varchar(128)                        not null comment '生成任务 ID',
    appId       bigint                              not null comment '应用 ID',
    userId      bigint                              not null comment '应用所有者 ID',
    action      varchar(64)                         not null comment '破坏性工具动作',
    requestJson mediumtext                          not null comment '规范化审批请求',
    status      varchar(32) default 'pending'       not null comment 'pending/approved/rejected/executing/consumed/expired',
    requestedAt datetime(6)                         not null,
    expiresAt   datetime(6)                         not null,
    decidedBy   bigint                              null,
    decidedAt   datetime(6)                         null,
    consumedAt  datetime(6)                         null,
    executionStartedAt datetime(6)                  null comment '工具调用开始执行时间',
    executionResult mediumtext                      null comment '可重放的工具结果 JSON',
    executionAttempt int default 0                  not null comment '副作用执行次数',
    toolRequestId varchar(128)                      null comment '模型工具调用 ID',
    toolName    varchar(128)                        null comment '模型工具名称',
    argumentsDigest char(64)                        null comment '工具参数 SHA-256',
    checkpointJson mediumtext                       null comment '版本化运行时续跑断点',
    version     bigint      default 0               not null,
    createTime  datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime  datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_task_approval (taskId, approvalId),
    UNIQUE KEY uk_task_tool_request (taskId, toolRequestId),
    INDEX idx_approval_expiration (status, expiresAt, id),
    INDEX idx_approval_execution (status, executionStartedAt, id),
    INDEX idx_approval_app (appId, requestedAt),
    CONSTRAINT chk_generation_tool_approval_expiry CHECK (expiresAt > requestedAt),
    CONSTRAINT chk_generation_tool_approval_checkpoint CHECK (
        (toolRequestId IS NULL AND toolName IS NULL AND argumentsDigest IS NULL AND checkpointJson IS NULL)
        OR (toolRequestId IS NOT NULL AND toolName IS NOT NULL
            AND argumentsDigest IS NOT NULL AND checkpointJson IS NOT NULL)
    ),
    CONSTRAINT chk_generation_tool_approval_status CHECK (
        status in ('pending', 'approved', 'rejected', 'executing', 'consumed', 'expired')
    ),
    CONSTRAINT chk_generation_tool_approval_attempt CHECK (executionAttempt >= 0),
    CONSTRAINT chk_generation_tool_approval_state CHECK (
        (status = 'pending' AND decidedBy IS NULL AND decidedAt IS NULL AND consumedAt IS NULL
            AND executionStartedAt IS NULL AND executionResult IS NULL AND executionAttempt = 0)
        OR (status = 'approved' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NULL
            AND executionStartedAt IS NULL AND executionResult IS NULL)
        OR (status = 'rejected' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NULL
            AND executionStartedAt IS NULL AND executionResult IS NULL)
        OR (status = 'executing' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NULL
            AND executionStartedAt IS NOT NULL AND executionResult IS NULL AND executionAttempt > 0)
        OR (status = 'consumed' AND decidedBy IS NOT NULL AND decidedAt IS NOT NULL AND consumedAt IS NOT NULL
            AND executionStartedAt IS NOT NULL AND executionResult IS NOT NULL AND executionAttempt > 0)
        OR (status = 'expired' AND consumedAt IS NULL AND executionStartedAt IS NULL AND executionResult IS NULL)
    )
) comment 'AI 工具一次性审批' collate = utf8mb4_unicode_ci;

-- AI 生成关键路径 span：保存跨实例、可恢复查询的阶段级耗时
create table if not exists generation_task_span
(
    id         bigint auto_increment comment 'id' primary key,
    spanId     varchar(36)                         not null comment 'span 幂等 ID',
    taskId     varchar(128)                        not null comment '生成任务 ID',
    stage      varchar(96)                         not null comment '阶段标识',
    category   varchar(32)                         not null comment '阶段类别',
    status     varchar(32)                         not null comment '阶段状态',
    startedAt  datetime(6)                         not null comment '阶段开始时间',
    endedAt    datetime(6)                         not null comment '阶段结束时间',
    durationMs bigint                               not null comment '阶段耗时毫秒',
    detail     varchar(1000)                        not null default '' comment '脱敏后的简要诊断',
    createTime datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    isDelete   tinyint     default 0                not null comment '是否删除',
    UNIQUE KEY uk_spanId (spanId),
    INDEX idx_task_started (taskId, startedAt, id),
    INDEX idx_stage_duration (stage, status, durationMs)
) comment 'AI 生成关键路径 span' collate = utf8mb4_unicode_ci;

-- AI 生成构建日志表：保存构建校验、自动修复后的构建结果和诊断报告
create table if not exists generation_build_log
(
    id             bigint auto_increment comment 'id' primary key,
    taskId         varchar(128)                       not null comment '生成任务 ID',
    appId          bigint                             not null comment '应用id',
    userId         bigint                             not null comment '创建用户id',
    projectPath    varchar(1024)                      null comment '项目路径',
    stage          varchar(64)                        null comment '构建阶段',
    success        tinyint                            null comment '是否成功',
    summary        text                               null comment '摘要',
    report         mediumtext                         null comment '诊断报告',
    qualityGate    varchar(64)                        null comment '质量门禁级别',
    willAutoRepair tinyint                            null comment '是否将自动修复',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete       tinyint  default 0                 not null comment '是否删除',
    INDEX idx_taskId_createTime (taskId, createTime),
    INDEX idx_appId_createTime (appId, createTime),
    INDEX idx_success_createTime (success, createTime)
) comment 'AI 生成构建日志' collate = utf8mb4_unicode_ci;

-- AI 模型调用表：预留模型 token、耗时和元数据，用于后续接入 provider usage
create table if not exists generation_model_call
    (
        id               bigint auto_increment comment 'id' primary key,
        callId           varchar(36)                        not null comment '模型调用幂等 ID',
        taskId           varchar(128)                       not null comment '生成任务 ID',
    appId            bigint                             not null comment '应用id',
    userId           bigint                             not null comment '创建用户id',
    provider         varchar(64)                        null comment '模型提供商',
    model            varchar(128)                       null comment '模型名称',
    callStatus       varchar(32) default 'SUCCESS'      not null comment 'SUCCESS/ERROR',
    providerRequestId varchar(128)                      null comment '提供商请求或响应 ID',
    promptTokens     int                                null comment '输入 token 数',
    completionTokens int                                null comment '输出 token 数',
    totalTokens      int                                null comment '总 token 数',
    latencyMs        bigint                             null comment '模型调用耗时毫秒',
    finishReason     varchar(64)                        null comment '结束原因',
    usageSource      varchar(32) default 'OFFICIAL'     not null comment 'token 来源：OFFICIAL/ESTIMATED/UNAVAILABLE',
    errorCategory    varchar(64)                        null comment '错误分类',
    requestHash      char(64)                           null comment '规范请求 SHA-256',
    promptTemplateHash char(64)                         null comment '系统提示 SHA-256',
    toolSchemaHash   char(64)                           null comment '工具 schema SHA-256',
    modelConfigHash  char(64)                           null comment '模型参数 SHA-256',
    requestMessageCount int default 0                   not null comment '请求消息数量',
    toolCount        int default 0                      not null comment '工具数量',
    rawMetadataJson  mediumtext                         null comment '原始元数据 JSON',
        createTime       datetime default CURRENT_TIMESTAMP not null comment '创建时间',
        isDelete         tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_callId (callId),
    INDEX idx_taskId_createTime (taskId, createTime),
    INDEX idx_model_createTime (model, createTime),
    INDEX idx_appId_createTime (appId, createTime),
    INDEX idx_generation_model_call_outcome (callStatus, createTime),
    INDEX idx_generation_model_call_prompt_model (promptTemplateHash, model, callStatus, createTime),
    CONSTRAINT chk_generation_model_call_status CHECK (callStatus in ('SUCCESS', 'ERROR')),
    CONSTRAINT chk_generation_model_call_counts CHECK (requestMessageCount >= 0 AND toolCount >= 0)
) comment 'AI 模型调用' collate = utf8mb4_unicode_ci;

-- 后续库表更新，不要仅仅在create中新建，因为现有库已经有库表了

-- AI 模型配置表：存储可配置的 AI 模型信息
create table if not exists ai_model
(
    id             bigint auto_increment comment 'id' primary key,
    modelName      varchar(128)                       not null comment '模型显示名称',
    provider       varchar(64)                        not null comment '模型提供商：deepseek/openai/custom',
    modelId        varchar(128)                       not null comment '模型标识符，如 deepseek-v4-flash',
    description    varchar(512)                       null comment '模型描述',
    baseUrl        varchar(512)                       not null comment 'API 基础地址',
    secretRef      varchar(4096)                      null comment 'Envelope-encrypted API credential reference',
    secretFingerprint char(64)                        null comment 'Stable HMAC-SHA256 credential fingerprint',
    secretKeyId    varchar(64)                        null comment 'Key-encryption-key identifier',
    maxTokens      int      default 8192              not null comment '最大 token 数',
    temperature    double   default 0.7               null comment '温度参数',
    isEnabled      tinyint  default 1                 not null comment '是否启用：0-禁用 1-启用',
    modelType      varchar(32)  default 'chat'        not null comment '模型类型：chat/reasoning/routing',
    supportsThinking tinyint  default 0               not null comment '是否支持 thinking 模式：0-不支持 1-支持',
    sortOrder      int      default 0                 not null comment '排序权重',
    configJson     text                               null comment '扩展配置 JSON',
    userId         bigint                             not null comment '创建用户id',
    editTime       datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint  default 0                 not null comment '是否删除',
    activeModelType varchar(32) generated always as
        (case when isEnabled = 1 and isDelete = 0 then modelType else null end) stored
        comment '启用模型类型并发唯一键',
    activeProvider varchar(64) generated always as
        (case when isDelete = 0 then provider else null end) stored
        comment '未删除模型提供商唯一键',
    activeModelId varchar(128) generated always as
        (case when isDelete = 0 then modelId else null end) stored
        comment '未删除模型标识唯一键',
    UNIQUE KEY uk_active_provider_model (activeProvider, activeModelId),
    UNIQUE KEY uk_active_model_type (activeModelType),
    INDEX idx_isEnabled (isEnabled),
    INDEX idx_userId (userId),
    INDEX idx_modelType (modelType),
    INDEX idx_modelType_enabled (modelType, isEnabled, isDelete)
) comment 'AI 模型配置' collate = utf8mb4_unicode_ci;

-- 已有库升级可执行以下语句
alter table ai_model add column if not exists modelName varchar(128) not null comment '模型显示名称';
alter table ai_model add column if not exists provider varchar(64) not null comment '模型提供商：deepseek/openai/custom';
alter table ai_model add column if not exists modelId varchar(128) not null comment '模型标识符，如 deepseek-v4-flash';
alter table ai_model add column if not exists description varchar(512) null comment '模型描述';
alter table ai_model add column if not exists baseUrl varchar(512) not null comment 'API 基础地址';
alter table ai_model add column if not exists secretRef varchar(4096) null comment 'Envelope-encrypted API credential reference';
alter table ai_model add column if not exists secretFingerprint char(64) null comment 'Stable HMAC-SHA256 credential fingerprint';
alter table ai_model add column if not exists secretKeyId varchar(64) null comment 'Key-encryption-key identifier';
alter table ai_model add column if not exists maxTokens int default 8192 not null comment '最大 token 数';
alter table ai_model add column if not exists temperature double default 0.7 null comment '温度参数';
alter table ai_model add column if not exists isEnabled tinyint default 1 not null comment '是否启用：0-禁用 1-启用';
alter table ai_model add column if not exists modelType varchar(32) default 'chat' not null comment '模型类型：chat/reasoning/routing';
alter table ai_model add column if not exists supportsThinking tinyint default 0 not null comment '是否支持 thinking 模式：0-不支持 1-支持';
alter table ai_model add column if not exists sortOrder int default 0 not null comment '排序权重';
alter table ai_model add column if not exists configJson text null comment '扩展配置 JSON';
alter table ai_model add column if not exists userId bigint not null comment '创建用户id';
alter table ai_model add column if not exists editTime datetime default CURRENT_TIMESTAMP not null comment '编辑时间';
alter table ai_model add column if not exists createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间';
alter table ai_model add column if not exists updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间';
alter table ai_model add column if not exists isDelete tinyint default 0 not null comment '是否删除';

-- 并发单活约束、API Key 列扩容和软删除身份唯一约束请执行版本化迁移：
-- sql/migrations/V20260713__ai_model_write_integrity.sql
-- sql/migrations/V20260714_2__ai_model_soft_delete_identity.sql
-- sql/migrations/V20260720_2__ai_model_secret_envelope.sql

-- AI 发布事务协调锁：必须通过数据库事务行锁保证多实例顺序。
create table if not exists ai_release_coordination_lock
(
    lockName   varchar(64)                         not null primary key,
    createTime datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime datetime(6) default CURRENT_TIMESTAMP(6) not null
        on update CURRENT_TIMESTAMP(6),
    constraint chk_ai_release_coordination_lock_name
        check (char_length(trim(lockName)) between 1 and 64)
) comment 'AI 发布事务协调锁' collate = utf8mb4_unicode_ci;

insert ignore into ai_release_coordination_lock (lockName)
values ('global');

create table if not exists ai_prompt_release_bundle
(
    id         tinyint                              not null primary key,
    revision   bigint       default 0               not null,
    updatedBy  bigint                               null,
    updateTime datetime(6)  default CURRENT_TIMESTAMP(6) not null
        on update CURRENT_TIMESTAMP(6),
    constraint chk_ai_prompt_release_bundle_id check (id = 1),
    constraint chk_ai_prompt_release_bundle_revision check (revision >= 0)
) comment 'atomic AI prompt release bundle head' collate = utf8mb4_unicode_ci;

insert into ai_prompt_release_bundle (id, revision, updatedBy)
values (1, 0, null)
on duplicate key update id = values(id);

create table if not exists ai_prompt_release
(
    promptKey         varchar(64)                         not null primary key,
    stableVersion     varchar(32)                         not null,
    canaryVersion     varchar(32)                         null,
    canaryPercentage  tinyint      default 0              not null,
    revision          bigint                              not null,
    updatedBy         bigint                              not null,
    changeNote        varchar(512)                        not null,
    createTime        datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime        datetime(6) default CURRENT_TIMESTAMP(6) not null
        on update CURRENT_TIMESTAMP(6),
    index idx_ai_prompt_release_revision (revision),
    constraint chk_ai_prompt_release_percentage
        check (canaryPercentage between 0 and 100),
    constraint chk_ai_prompt_release_revision check (revision > 0),
    constraint chk_ai_prompt_release_operator check (updatedBy > 0),
    constraint chk_ai_prompt_release_identifiers check (
        char_length(trim(promptKey)) between 1 and 64
        and char_length(trim(stableVersion)) between 1 and 32
        and (canaryVersion is null or char_length(trim(canaryVersion)) between 1 and 32)
    ),
    constraint chk_ai_prompt_release_note
        check (char_length(trim(changeNote)) between 1 and 512),
    constraint chk_ai_prompt_release_canary check (
        (canaryPercentage = 0 and canaryVersion is null)
        or (canaryPercentage between 1 and 100
            and canaryVersion is not null
            and canaryVersion <> stableVersion)
    )
) comment 'current runtime AI prompt release pointers' collate = utf8mb4_unicode_ci;

create table if not exists ai_prompt_release_history
(
    revision          bigint                              not null primary key,
    promptKey         varchar(64)                         not null,
    stableVersion     varchar(32)                         not null,
    canaryVersion     varchar(32)                         null,
    canaryPercentage  tinyint      default 0              not null,
    action             varchar(16)                         not null,
    sourceRevision     bigint                              null,
    updatedBy          bigint                              not null,
    changeNote         varchar(512)                        not null,
    evidenceId         char(36)                            null,
    createTime         datetime(6) default CURRENT_TIMESTAMP(6) not null,
    index idx_ai_prompt_release_history_key (promptKey, revision),
    constraint chk_ai_prompt_release_history_percentage
        check (canaryPercentage between 0 and 100),
    constraint chk_ai_prompt_release_history_operator check (updatedBy > 0),
    constraint chk_ai_prompt_release_history_identifiers check (
        char_length(trim(promptKey)) between 1 and 64
        and char_length(trim(stableVersion)) between 1 and 32
        and (canaryVersion is null or char_length(trim(canaryVersion)) between 1 and 32)
    ),
    constraint chk_ai_prompt_release_history_note
        check (char_length(trim(changeNote)) between 1 and 512),
    constraint chk_ai_prompt_release_history_action
        check (action in ('PUBLISH', 'ROLLBACK')),
    constraint chk_ai_prompt_release_history_source check (
        (action = 'PUBLISH' and sourceRevision is null)
        or (action = 'ROLLBACK' and sourceRevision is not null and sourceRevision > 0)
    ),
    constraint chk_ai_prompt_release_history_canary check (
        (canaryPercentage = 0 and canaryVersion is null)
        or (canaryPercentage between 1 and 100
            and canaryVersion is not null
            and canaryVersion <> stableVersion)
    )
) comment 'immutable AI prompt release audit history' collate = utf8mb4_unicode_ci;

create table if not exists generation_benchmark_evidence
(
    id                       bigint auto_increment primary key,
    evidenceId               char(36)                           not null,
    subjectType              varchar(32)                        not null,
    subjectKey               varchar(128)                       not null,
    candidateFingerprint     char(64)                           not null,
    signatureVersion         smallint                           not null,
    candidatePhysicalRequestCount bigint                        not null,
    datasetFingerprint       char(64)                           not null,
    graderFingerprint        varchar(128)                       not null,
    runtimeConfigFingerprint char(64)                           not null,
    gitCommit                varchar(64)                        not null,
    modelFingerprint         char(64)                           not null,
    promptBundleFingerprint  char(64)                           not null,
    reportSha256             char(64)                           not null,
    reportJson               mediumtext                         not null,
    passed                   tinyint                            not null,
    violationsJson           mediumtext                         not null,
    signature                char(64)                           not null,
    evaluatedAt              datetime(6)                        not null,
    expiresAt                datetime(6)                        not null,
    createTime               datetime(6) default CURRENT_TIMESTAMP(6) not null,
    isDelete                 tinyint     default 0              not null,
    unique key uk_generation_benchmark_evidence_id (evidenceId),
    index idx_generation_benchmark_evidence_subject
        (subjectType, subjectKey, candidateFingerprint, passed, expiresAt),
    index idx_generation_benchmark_evidence_expiry (expiresAt, isDelete),
    constraint chk_generation_benchmark_evidence_subject
        check (subjectType in ('PROMPT_RELEASE', 'AI_MODEL_ENABLE')),
    constraint chk_generation_benchmark_evidence_attestation
        check (
            (signatureVersion = 1 and candidatePhysicalRequestCount = 0)
            or (signatureVersion = 2 and (
                (subjectType = 'AI_MODEL_ENABLE' and candidatePhysicalRequestCount > 0)
                or (subjectType = 'PROMPT_RELEASE' and candidatePhysicalRequestCount = 0)
            ))
        ),
    constraint chk_generation_benchmark_evidence_passed check (passed in (0, 1)),
    constraint chk_generation_benchmark_evidence_window check (expiresAt > evaluatedAt)
) comment 'signed immutable AI release benchmark evidence' collate = utf8mb4_unicode_ci;
create table if not exists ai_release_audit
(
    id                   bigint auto_increment primary key,
    auditId              char(36)                           not null,
    evidenceId           char(36)                           not null,
    subjectType          varchar(32)                        not null,
    subjectKey           varchar(128)                       not null,
    candidateFingerprint char(64)                           not null,
    action               varchar(32)                        not null,
    operatorUserId       bigint                             not null,
    releaseReference     varchar(128)                       not null,
    createTime           datetime(6) default CURRENT_TIMESTAMP(6) not null,
    unique key uk_ai_release_audit_id (auditId),
    index idx_ai_release_audit_evidence (evidenceId, createTime),
    index idx_ai_release_audit_subject (subjectType, subjectKey, createTime),
    constraint chk_ai_release_audit_operator check (operatorUserId > 0)
) comment 'immutable AI release evidence usage audit' collate = utf8mb4_unicode_ci;
