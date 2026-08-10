-- =============================================================================
-- rush_ai_code_mother 全量建表脚本（整合版）
-- =============================================================================
-- 用途：在【空库】上一次性建出完整结构，替代 36 个迁移文件的逐个回放。
--
-- 生成方式：在干净的 MySQL 8.0 容器中，按 Flyway 对新库的真实选择顺序回放
--           B20260716_5 基线 + 22 个版本号更高的 V 迁移，再从结果库导出结构。
--           因此本文件等价于 Flyway 建库结果，而非人工誊抄。
--
-- 适用引擎：MySQL 8.0+（使用 generated stored 列与 CHECK 约束，
--           不使用 MariaDB 专有的 ADD COLUMN IF NOT EXISTS）
--
-- 执行方式：mysql -u root -p rush_ai_code_mother < sql/schema.sql
--           脚本不含 create database / use，请自行选库，避免误建到别的库。
--
-- 与迁移链的两处有意差异（详见文件末尾说明）：
--   1. ai_model 去掉 uk_active_model_type 单活唯一键，改为运行时池索引
--   2. app 补上 devServerPort 列
--
-- 表顺序按外键依赖排列：user -> tenant -> tenant_membership -> app -> 其余。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 用户与租户
-- -----------------------------------------------------------------------------

-- 用户表：账号主体与积分余额
create table if not exists user
(
    id            bigint auto_increment comment 'id' primary key,
    userAccount   varchar(256)                       not null comment '账号',
    userPassword  varchar(512)                       not null comment '密码',
    userName      varchar(256)                       null comment '用户昵称',
    userAvatar    varchar(1024)                      null comment '用户头像',
    userProfile   varchar(512)                       null comment '用户简介',
    userRole      varchar(256) default 'user'        not null comment '用户角色：user/admin',
    creditBalance bigint       default 0             not null comment '用户积分余额',
    editTime      datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete      tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName),
    CONSTRAINT chk_user_credit_balance_nonnegative CHECK (creditBalance >= 0)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 用户积分流水：每条变动都要能追溯到业务单据
create table if not exists user_credit_transaction
(
    id           bigint auto_increment comment 'id' primary key,
    userId       bigint                             not null comment '用户id',
    changeAmount bigint                             not null comment '积分变动，正数增加，负数扣除',
    balanceAfter bigint                             not null comment '变动后余额',
    type         varchar(64)                        not null comment '变动类型：初始化/调整/生成预授权/结算/兼容扣费',
    bizId        varchar(128)                       not null comment '业务id，例如 generation taskId',
    remark       varchar(512)                       not null comment '备注',
    adminUserId  bigint                             null comment '管理员操作人id',
    tokenCount   bigint                             null comment '本次 token 数',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_type_bizId (type, bizId),
    INDEX idx_userId_createTime (userId, createTime),
    INDEX idx_adminUserId_createTime (adminUserId, createTime),
    CONSTRAINT chk_user_credit_transaction_balance_nonnegative CHECK (balanceAfter >= 0),
    -- 每种流水类型的字段组合都固定，避免出现「管理员调整却带 token 数」这类脏数据
    CONSTRAINT chk_user_credit_transaction_shape CHECK (
        (type = 'ACCOUNT_INITIALIZATION' and changeAmount > 0 and adminUserId is not null and tokenCount is null)
            or (type = 'ADMIN_ADJUST' and changeAmount <> 0 and adminUserId is not null and tokenCount is null)
            or (type = 'GENERATION_CHARGE' and changeAmount <= 0 and adminUserId is null
                and tokenCount is not null and tokenCount >= 0)
            or (type = 'GENERATION_RESERVATION' and changeAmount < 0 and adminUserId is null and tokenCount is null)
            or (type = 'GENERATION_SETTLEMENT' and adminUserId is null
                and tokenCount is not null and tokenCount >= 0)
        )
) comment '用户积分流水' collate = utf8mb4_unicode_ci;

-- 租户表：授权与数据隔离边界，个人租户由应用侧按 personal:{userId} 自动创建
create table if not exists tenant
(
    id          bigint auto_increment primary key,
    tenantKey   varchar(191)                             not null,
    tenantType  varchar(32)                              not null,
    displayName varchar(128)                             not null,
    ownerUserId bigint                                   not null,
    status      varchar(32)     default 'active'         not null,
    createTime  datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime  datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    isDelete    tinyint     default 0                    not null,
    UNIQUE KEY uk_tenant_key (tenantKey),
    INDEX idx_tenant_owner_type (ownerUserId, tenantType, isDelete),
    INDEX idx_tenant_status (status, isDelete, id),
    CONSTRAINT fk_tenant_owner_user FOREIGN KEY (ownerUserId) REFERENCES user (id),
    CONSTRAINT chk_tenant_owner CHECK (ownerUserId > 0),
    CONSTRAINT chk_tenant_status CHECK (status in ('active', 'suspended')),
    CONSTRAINT chk_tenant_type CHECK (tenantType in ('personal', 'organization'))
) comment 'tenant authorization and data isolation boundary' collate = utf8mb4_unicode_ci;

-- 租户成员与角色分配
create table if not exists tenant_membership
(
    id         bigint auto_increment primary key,
    tenantId   bigint                                   not null,
    userId     bigint                                   not null,
    role       varchar(32)                              not null,
    status     varchar(32)     default 'active'         not null,
    joinedAt   datetime(6)                              not null,
    createTime datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    isDelete   tinyint     default 0                    not null,
    UNIQUE KEY uk_tenant_membership_identity (tenantId, userId),
    INDEX idx_tenant_membership_user (userId, status, isDelete, tenantId),
    INDEX idx_tenant_membership_tenant (tenantId, status, role, isDelete),
    CONSTRAINT fk_tenant_membership_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id),
    CONSTRAINT fk_tenant_membership_user FOREIGN KEY (userId) REFERENCES user (id),
    CONSTRAINT chk_tenant_membership_identity CHECK (tenantId > 0 and userId > 0),
    CONSTRAINT chk_tenant_membership_role CHECK (role in ('viewer', 'developer', 'admin', 'owner')),
    CONSTRAINT chk_tenant_membership_status CHECK (status in ('invited', 'active', 'suspended'))
) comment 'tenant membership and role assignment' collate = utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 应用主体与附属能力
-- -----------------------------------------------------------------------------

-- 应用表：生成状态由租约 + fencing epoch 保护，避免多实例并发改写
create table if not exists app
(
    id                       bigint auto_increment comment 'id' primary key,
    appName                  varchar(256)                       null comment '应用名称',
    cover                    varchar(512)                       null comment '应用封面',
    initPrompt               text                               null comment '应用初始化的 prompt',
    codeGenType              varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey                varchar(64)                        null comment '部署标识',
    deployedTime             datetime                           null comment '部署时间',
    isGenerating             tinyint  default 0                 not null comment '是否正在生成',
    generatingMessage        mediumtext                         null comment '当前生成中的 AI 响应快照',
    generatingStage          varchar(64)                        null comment '当前生成阶段',
    generatingTaskId         varchar(128)                       null comment '当前生成状态所有者任务 ID',
    generationExecutionEpoch bigint                             null comment 'current generation fencing epoch',
    generationLeaseUntil     datetime(6)                        null comment '生成状态租约到期时间',
    -- Dev Server 预览端口：迁移链遗漏，此处补齐（AppMapper 三处语句强依赖）
    devServerPort            int                                null comment 'Vue 开发服务器端口号（预览用）',
    priority                 int      default 0                 not null comment '优先级',
    userId                   bigint                             not null comment '创建用户id',
    tenantId                 bigint                             not null,
    editTime                 datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime               datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime               datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete                 tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey),
    INDEX idx_appName (appName),
    INDEX idx_userId (userId),
    INDEX idx_generation_lease (isGenerating, generationLeaseUntil),
    INDEX idx_app_tenant_cursor (tenantId, isDelete, createTime, id),
    CONSTRAINT fk_app_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id),
    -- 生成中必须持有 epoch；空闲时必须清空，防止陈旧 epoch 复活
    CONSTRAINT chk_app_generation_execution_epoch CHECK (
        (isGenerating = 0 and generationExecutionEpoch is null)
            or (isGenerating = 1 and generationExecutionEpoch is not null and generationExecutionEpoch >= 0)
        ),
    -- 生成状态三元组同生同灭：要么全空闲，要么任务 ID 与租约都在
    CONSTRAINT chk_app_generation_state_ownership CHECK (
        (isGenerating = 0 and generatingTaskId is null and generationLeaseUntil is null)
            or (isGenerating = 1 and generatingTaskId is not null and generationLeaseUntil is not null)
        )
) comment '应用' collate = utf8mb4_unicode_ci;

-- 对话历史：三个游标索引分别服务应用维度、用户维度与管理端翻页
create table if not exists chat_history
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

-- 应用能力开关（database/analytics/git/mobile）
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

-- 应用 Database 资源
create table if not exists app_database_resource
(
    id                 bigint auto_increment comment 'id' primary key,
    appId              bigint                                  not null comment '应用id',
    userId             bigint                                  not null comment '创建用户id',
    resourceId         varchar(128)                            not null comment 'Database 资源标识',
    resourceName       varchar(256)                            null comment 'Database 资源名称',
    databaseUrl        varchar(512)                            not null comment 'Database 访问 URL',
    dbEngine           varchar(64) default 'SQLite'            not null comment '数据库引擎',
    backendRuntime     varchar(64) default 'go'                not null comment '后端运行时',
    sqlExecutionPolicy varchar(64) default 'ask_every_time'     not null comment 'SQL 执行策略：ask_every_time/always_allow',
    status             varchar(32) default 'active'            not null comment '状态：active/recycled/error',
    lastUsedTime       datetime                                null comment '最后使用时间',
    createTime         datetime    default CURRENT_TIMESTAMP   not null comment '创建时间',
    updateTime         datetime    default CURRENT_TIMESTAMP   not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete           tinyint     default 0                   not null comment '是否删除',
    UNIQUE KEY uk_appId (appId),
    UNIQUE KEY uk_resourceId (resourceId),
    INDEX idx_userId_status (userId, status),
    INDEX idx_lastUsedTime (lastUsedTime)
) comment '应用 Database 资源' collate = utf8mb4_unicode_ci;

-- 应用数据分析配置
create table if not exists app_analytics_config
(
    id            bigint auto_increment comment 'id' primary key,
    appId         bigint                             not null comment '应用id',
    userId        bigint                             not null comment '创建用户id',
    enabled       tinyint  default 0                 not null comment '是否启用数据分析',
    trackingKey   varchar(128)                       null comment '埋点追踪 key',
    retentionDays int      default 30                not null comment '数据保留天数',
    configJson    text                               null comment '分析配置 JSON',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete      tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_appId (appId),
    INDEX idx_userId_enabled (userId, enabled)
) comment '应用数据分析配置' collate = utf8mb4_unicode_ci;

-- 应用 Git 仓库
create table if not exists app_git_repository
(
    id               bigint auto_increment comment 'id' primary key,
    appId            bigint                                 not null comment '应用id',
    userId           bigint                                 not null comment '创建用户id',
    provider         varchar(64)  default 'internal_git'    null comment 'Git 提供方',
    repositoryUrl    varchar(1024)                          null comment '仓库地址',
    defaultBranch    varchar(128) default 'main'            null comment '默认分支',
    latestCommitHash varchar(128)                           null comment '最新提交哈希',
    status           varchar(32)  default 'active'          not null comment '状态',
    configJson       text                                   null comment '扩展配置 JSON',
    createTime       datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_appId (appId),
    INDEX idx_userId_status (userId, status)
) comment '应用 Git 仓库' collate = utf8mb4_unicode_ci;

-- 应用运行渠道（web/mobile/wechat）
create table if not exists app_runtime_channel
(
    id             bigint auto_increment comment 'id' primary key,
    appId          bigint                                not null comment '应用id',
    userId         bigint                                not null comment '创建用户id',
    channelType    varchar(64)                           not null comment '渠道类型：web/mobile/wechat',
    deployUrl      varchar(1024)                         null comment '部署访问地址',
    qrCodeUrl      varchar(1024)                         null comment '移动端二维码地址',
    openMode       varchar(64)                           null comment '打开方式：browser/wechat',
    status         varchar(32) default 'active'          not null comment '状态',
    configJson     text                                  null comment '渠道配置 JSON',
    lastDeployTime datetime                              null comment '最后部署时间',
    createTime     datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint     default 0                 not null comment '是否删除',
    UNIQUE KEY uk_app_channel (appId, channelType),
    INDEX idx_userId_channelType (userId, channelType)
) comment '应用运行渠道' collate = utf8mb4_unicode_ci;

-- Dev Server 会话：以 appId 为主键，保证一个应用只有一个预览进程所有者
create table if not exists dev_server_session
(
    appId              bigint                                   not null comment 'application id' primary key,
    userId             bigint                                   not null comment 'owner user id',
    nodeId             varchar(128)                             not null comment 'stable deployment node id',
    leaseOwner         varchar(160)                             null comment 'process-unique lease owner',
    state              varchar(32)                              not null comment 'session lifecycle state',
    port               int                                      not null comment 'preview port on owner node',
    projectDirectory   varchar(1024)                            not null comment 'normalized generated project path',
    sandboxBackend     varchar(32)                              null comment 'sandbox backend owning resources',
    cleanupResourceIds varchar(2048)                            null comment 'newline-delimited opaque sandbox resource ids',
    leaseUntil         datetime(6)                              null comment 'ownership lease expiration',
    heartbeatAt        datetime(6)                              null comment 'last successful owner heartbeat',
    version            bigint      default 0                    not null comment 'optimistic fencing version',
    lastError          varchar(512)                             null comment 'sanitized lifecycle failure',
    createTime         datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime         datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    INDEX idx_dev_server_session_user_state_lease (userId, state, leaseUntil),
    INDEX idx_dev_server_session_state_lease (state, leaseUntil),
    CONSTRAINT chk_dev_server_session_port CHECK (port between 1 and 65535)
) comment 'durable Dev Server ownership and orphan recovery registry' collate = utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 生成任务与可观测性
-- -----------------------------------------------------------------------------

-- AI 生成任务：durable queue + worker 租约 + fencing epoch + 发布日志 + 语义记忆 outbox
create table if not exists generation_task
(
    id                       bigint auto_increment comment 'id' primary key,
    taskId                   varchar(128)                       not null comment '生成任务 ID',
    appId                    bigint                             not null comment '应用id',
    userId                   bigint                             not null comment '创建用户id',
    tenantId                 bigint                             not null,

    -- 提交幂等：只存 Idempotency-Key 的 SHA-256，原始 key 严禁落库或打日志
    idempotencyKeyHash       char(64) character set ascii collate ascii_bin null comment 'Idempotency-Key SHA-256',
    requestFingerprint       char(64) character set ascii collate ascii_bin null comment '提交请求规范指纹 SHA-256',

    originalCodeGenType      varchar(64)                        null comment '原始代码生成类型',
    targetCodeGenType        varchar(64)                        null comment '目标代码生成类型',
    status                   varchar(32) default 'queued'       not null comment '状态：queued/running/waiting_approval/success/failed/cancelled/deadline_exceeded',
    stage                    varchar(64)                        null comment '当前阶段',
    stageMessage             text                               null comment '当前阶段提示信息',
    userPrompt               mediumtext                         null comment '用户原始提示词',
    enhancedPrompt           mediumtext                         null comment '增强后的生成提示词',
    requiresBuildValidation  tinyint     default 0              not null comment '是否需要构建校验',
    qualityGate              varchar(64)                        null comment '质量门禁级别',
    thinkingMode             varchar(16)                        null comment '实际使用的思考档位',

    -- 交付质量指标：NULL 表示「未采集」，禁止回填默认值当作真实观测
    changedFileCount         int                                null comment '有效变更文件数',
    firstBuildPassed         tinyint                            null comment '是否免修复通过构建',
    repairRounds             int                                null comment '实际修复轮次',
    firstPreviewMillis       bigint                             null comment '提交到可预览耗时毫秒',
    failureCategory          varchar(64)                        null comment '失败分类',
    reworkedAt               datetime(6)                        null comment '交付后被追加改修的时间',
    distilledAt              datetime(6)                        null comment '经验已蒸馏时间',

    orchestrationMode        varchar(64)                        null comment '编排模式',
    route                    varchar(64)                        null comment '运行时路由',
    runtimeSchemaVersion     int                                null comment '可重建执行命令 schema 版本',
    runtimePayloadJson       mediumtext                         null comment '跨实例可重建执行命令 JSON',

    -- durable queue 投递
    dispatchAt               datetime(6)                        null comment '最近一次进入 durable queue 的时间',
    dispatchAttempt          int         default 0              not null comment 'durable queue 投递尝试次数',
    dispatchError            varchar(1000)                      null comment '最近一次 durable queue 投递错误',

    submittedAt              datetime(6)                        not null comment '任务提交时间',
    deadlineAt               datetime(6)                        null comment '任务绝对截止时间',
    cancellationRequested    tinyint     default 0              not null comment '是否请求取消',
    cancellationReason       varchar(512)                       null comment '取消原因',

    -- worker 租约与 fencing：epoch 单调递增，陈旧 worker 的写入会被拒绝
    leaseOwner               varchar(128)                       null comment 'worker 租约所有者',
    leaseUntil               datetime(6)                        null comment 'worker 租约到期时间',
    heartbeatAt              datetime(6)                        null comment 'worker 最近心跳时间',
    executionEpoch           bigint      default 0              not null comment 'monotonic worker fencing epoch',
    attempt                  int         default 0              not null comment 'worker 领取次数',
    version                  bigint      default 0              not null comment '运行时乐观锁版本',

    startTime                datetime default CURRENT_TIMESTAMP not null comment '开始时间',
    endTime                  datetime                           null comment '结束时间',
    durationMs               bigint                             null comment '耗时毫秒',
    errorMessage             text                               null comment '错误信息',

    -- 语义记忆索引 outbox：靠 claim 机制推进，不要用批量 UPDATE 重置
    memorySummary            mediumtext                         null comment 'AI 可读的生成记忆摘要',
    memoryIndexedAt          datetime(6)                        null comment '语义记忆成功写入 Milvus 的时间',
    memoryIndexAttempts      int         default 0              not null comment '语义记忆索引尝试次数',
    memoryIndexError         varchar(1000)                      null comment '语义记忆最近索引错误',
    memoryIndexNextAttemptAt datetime(6)                        null comment 'next semantic-memory indexing attempt',
    memoryIndexLeaseOwner    varchar(128)                       null comment 'semantic-memory outbox lease owner',
    memoryIndexLeaseUntil    datetime(6)                        null comment 'semantic-memory outbox lease expiry',

    totalTokens              bigint      default 0              not null comment '任务累计 token 数',
    creditCost               bigint      default 0              not null comment '任务消耗积分',
    creditCharged            tinyint     default 0              not null comment '是否已结算积分',

    -- 工作区发布日志：文件系统与元数据两阶段提交的对账依据
    publicationStatus         varchar(32)                       null comment 'prepared/filesystem_activated/committed/rollback_required/rolled_back/superseded',
    publicationCodeGenType    varchar(32)                       null comment 'published workspace code generation type',
    publicationExecutionEpoch bigint                            null comment 'publication fencing epoch',
    publicationPublishedAt    datetime(6)                       null comment 'stable publication pointer timestamp',
    publicationAttempts       int        default 0              not null comment 'publication reconciliation attempts',
    publicationVersion        bigint     default 0              not null comment 'publication journal optimistic version',
    publicationError          varchar(1024)                     null comment 'latest publication reconciliation failure',
    publicationReconcileAfter datetime(6)                       null comment 'next eligible publication reconciliation time',
    publicationCommittedAt    datetime(6)                       null comment 'filesystem and metadata publication commit time',

    createTime               datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime               datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete                 tinyint     default 0              not null comment '是否删除',
    -- 语义记忆契约版本：schema/collection/embedding 不兼容变更时必须递增以触发重建
    memoryIndexContractVersion int       default 0              not null comment 'indexed semantic-memory contract version; 0 means pending',

    UNIQUE KEY uk_taskId (taskId),
    UNIQUE KEY uk_generation_task_submission_idempotency (tenantId, userId, appId, idempotencyKeyHash),
    INDEX idx_appId_createTime (appId, createTime),
    INDEX idx_userId_createTime (userId, createTime),
    INDEX idx_status_createTime (status, createTime),
    INDEX idx_route_success_duration (route, status, isDelete, endTime, id),
    INDEX idx_runtime_lease (status, leaseUntil, isDelete),
    INDEX idx_app_runtime_status (appId, status, submittedAt),
    INDEX idx_user_runtime_status (userId, status, isDelete, submittedAt),
    INDEX idx_memory_outbox (memoryIndexedAt, memoryIndexAttempts, status, isDelete, endTime),
    INDEX idx_runtime_dispatch (status, dispatchAt, leaseOwner, isDelete, submittedAt),
    INDEX idx_generation_task_tenant_runtime (tenantId, status, isDelete, submittedAt, id),
    INDEX idx_generation_task_publication_reconcile (publicationStatus, publicationReconcileAfter, id),
    INDEX idx_memory_outbox_claim (memoryIndexedAt, memoryIndexNextAttemptAt, memoryIndexLeaseUntil,
                                  memoryIndexAttempts, status, isDelete, endTime),
    INDEX idx_memory_outbox_contract_claim (memoryIndexContractVersion, memoryIndexedAt, memoryIndexNextAttemptAt,
                                           memoryIndexLeaseUntil, memoryIndexAttempts, status, isDelete, endTime),
    INDEX idx_generation_task_distill_claim (distilledAt, status, isDelete, endTime, id),
    CONSTRAINT fk_generation_task_tenant FOREIGN KEY (tenantId) REFERENCES tenant (id),
    CONSTRAINT chk_generation_task_execution_epoch CHECK (executionEpoch >= 0),
    -- 幂等键与请求指纹必须成对出现，否则无法判定「同键不同请求」
    CONSTRAINT chk_generation_task_idempotency_pair CHECK (
        (idempotencyKeyHash is null and requestFingerprint is null)
            or (idempotencyKeyHash is not null and requestFingerprint is not null)
        ),
    CONSTRAINT chk_generation_task_memory_contract_version CHECK (memoryIndexContractVersion >= 0),
    CONSTRAINT chk_generation_task_outcome_quality CHECK (
        (changedFileCount is null or changedFileCount >= 0)
            and (repairRounds is null or repairRounds >= 0)
            and (firstPreviewMillis is null or firstPreviewMillis >= 0)
            and (firstBuildPassed is null or firstBuildPassed in (0, 1))
        ),
    CONSTRAINT chk_generation_task_publication_attempts CHECK (publicationAttempts >= 0),
    -- 发布字段整体同生同灭；且仅 committed 状态允许有提交时间
    CONSTRAINT chk_generation_task_publication_state CHECK (
        (publicationStatus is null and publicationCodeGenType is null and publicationExecutionEpoch is null
            and publicationPublishedAt is null and publicationCommittedAt is null)
            or (publicationStatus in ('prepared', 'filesystem_activated', 'committed',
                                      'rollback_required', 'rolled_back', 'superseded')
                and publicationCodeGenType is not null
                and publicationExecutionEpoch is not null and publicationExecutionEpoch > 0
                and publicationPublishedAt is not null
                and ((publicationStatus = 'committed' and publicationCommittedAt is not null)
                    or (publicationStatus <> 'committed' and publicationCommittedAt is null)))
        ),
    CONSTRAINT chk_generation_task_publication_version CHECK (publicationVersion >= 0)
) comment 'AI 生成任务' collate = utf8mb4_unicode_ci;

-- @@APPEND@@
