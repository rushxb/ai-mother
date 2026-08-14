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
    tenantId     bigint                             null comment '生成任务所属租户；非生成和历史流水为空',
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
    INDEX idx_tenant_generation_budget (tenantId, type, isDelete, createTime, bizId),
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
    intentSignature          char(64)                           null comment '结构化意图场景签名',
    intentProfileVersion     varchar(32)                        null comment '意图画像协议版本',
    routeDecisionVersion     varchar(32)                        null comment '路由决策协议版本',
    routeEvidenceJson        text                               null comment '路由证据 JSON',
    routeAlternativesJson    text                               null comment '备选路由 JSON',
    routeReleaseIdentity     varchar(64)                        null comment '路由发布身份',
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
    INDEX idx_generation_task_scenario_attribution (intentSignature, endTime, route, status, id),
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

-- 生成关键路径 span：detail 已脱敏，可直接暴露给前端
create table if not exists generation_task_span
(
    id         bigint auto_increment comment 'id' primary key,
    spanId     varchar(36)                              not null comment 'span 幂等 ID',
    taskId     varchar(128)                             not null comment '生成任务 ID',
    stage      varchar(96)                              not null comment '阶段标识',
    category   varchar(32)                              not null comment '阶段类别',
    status     varchar(32)                              not null comment '阶段状态',
    startedAt  datetime(6)                              not null comment '阶段开始时间',
    endedAt    datetime(6)                              not null comment '阶段结束时间',
    durationMs bigint                                   not null comment '阶段耗时毫秒',
    detail     varchar(1000) default ''                 not null comment '脱敏后的简要诊断',
    createTime datetime(6)   default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    isDelete   tinyint       default 0                  not null comment '是否删除',
    UNIQUE KEY uk_spanId (spanId),
    INDEX idx_task_started (taskId, startedAt, id),
    INDEX idx_stage_duration (stage, status, durationMs)
) comment 'AI 生成关键路径 span' collate = utf8mb4_unicode_ci;

-- 构建日志与自动修复诊断
create table if not exists generation_build_log
(
    id            bigint auto_increment comment 'id' primary key,
    taskId        varchar(128)                       not null comment '生成任务 ID',
    appId         bigint                             not null comment '应用id',
    userId        bigint                             not null comment '创建用户id',
    projectPath   varchar(1024)                      null comment '项目路径',
    stage         varchar(64)                        null comment '构建阶段',
    success       tinyint                            null comment '是否成功',
    summary       text                               null comment '摘要',
    report        mediumtext                         null comment '诊断报告',
    qualityGate   varchar(64)                        null comment '质量门禁级别',
    willAutoRepair tinyint                           null comment '是否将自动修复',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete      tinyint  default 0                 not null comment '是否删除',
    INDEX idx_taskId_createTime (taskId, createTime),
    INDEX idx_appId_createTime (appId, createTime),
    INDEX idx_success_createTime (success, createTime)
) comment 'AI 生成构建日志' collate = utf8mb4_unicode_ci;

-- 模型调用流水：含请求指纹等溯源列，用于定位「同一提示词不同表现」
create table if not exists generation_model_call
(
    id                  bigint auto_increment comment 'id' primary key,
    callId              varchar(36)                        not null comment '模型调用幂等 ID',
    taskId              varchar(128)                       not null comment '生成任务 ID',
    appId               bigint                             null comment '应用id；外围模型调用允许为空',
    userId              bigint                             not null comment '创建用户id',
    invocationPurpose   varchar(32) default 'GENERATION'  not null comment '稳定调用目的',
    billingMode         varchar(16) default 'BILLABLE'    not null comment 'BILLABLE/EXEMPT',
    billingExemptionReason varchar(64)                      null comment 'EXEMPT 的有界审计原因',
    provider            varchar(64)                        null comment '模型提供商',
    model               varchar(128)                       null comment '模型名称',
    callStatus          varchar(32) default 'SUCCESS'      not null comment 'STARTED/SUCCESS/ERROR',
    providerRequestId   varchar(128)                       null comment 'provider response/request id',
    promptTokens        int                                null comment '输入 token 数',
    completionTokens    int                                null comment '输出 token 数',
    totalTokens         int                                null comment '总 token 数',
    latencyMs           bigint                             null comment '模型调用耗时毫秒',
    finishReason        varchar(64)                        null comment '结束原因',
    usageSource         varchar(32) default 'OFFICIAL'     not null comment 'token 来源：OFFICIAL/ESTIMATED/UNAVAILABLE',
    errorCategory       varchar(64)                        null comment 'bounded production error category',
    requestHash         char(64)                           null comment 'sha-256 of canonical rendered request',
    promptTemplateHash  char(64)                           null comment 'sha-256 of rendered system prompt set',
    toolSchemaHash      char(64)                           null comment 'sha-256 of canonical tool schemas',
    modelConfigHash     char(64)                           null comment 'sha-256 of provider model request parameters',
    requestMessageCount int         default 0              not null comment 'number of messages sent to the provider',
    toolCount           int         default 0              not null comment 'number of exposed tool schemas',
    rawMetadataJson     mediumtext                         null comment '原始元数据 JSON',
    createTime          datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    isDelete            tinyint     default 0              not null comment '是否删除',
    UNIQUE KEY uk_callId (callId),
    INDEX idx_taskId_createTime (taskId, createTime),
    INDEX idx_model_createTime (model, createTime),
    INDEX idx_appId_createTime (appId, createTime),
    INDEX idx_model_invocation_recovery (invocationPurpose, billingMode, callStatus, createTime),
    INDEX idx_generation_model_call_prompt_model (promptTemplateHash, model, callStatus, createTime),
    CONSTRAINT chk_generation_model_call_counts CHECK (requestMessageCount >= 0 and toolCount >= 0),
    CONSTRAINT chk_generation_model_call_purpose CHECK (invocationPurpose in
        ('GENERATION', 'PROMPT_OPTIMIZATION', 'APP_NAME_ENRICHMENT', 'CONNECTION_TEST')),
    CONSTRAINT chk_generation_model_call_billing CHECK (
        (billingMode = 'BILLABLE' and billingExemptionReason is null)
        or (billingMode = 'EXEMPT' and billingExemptionReason is not null)),
    CONSTRAINT chk_generation_model_call_status CHECK (callStatus in ('STARTED', 'SUCCESS', 'ERROR'))
) comment 'AI 模型调用' collate = utf8mb4_unicode_ci;

-- 生成结果用户反馈：一个任务每个用户只留一条
create table if not exists generation_feedback
(
    id         bigint auto_increment comment 'id' primary key,
    taskId     varchar(128)                          not null comment '生成任务 ID',
    appId      bigint                                not null comment '应用 ID',
    userId     bigint                                not null comment '用户 ID',
    rating     tinyint                               not null comment '用户评分：1-5',
    outcome    varchar(64) default 'unspecified'     not null comment '反馈结果标签',
    comment    text                                  null comment '用户反馈文本',
    createTime datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint     default 0                 not null comment '是否删除',
    UNIQUE KEY uk_generation_feedback_task_user (taskId, userId),
    INDEX idx_generation_feedback_app_update (appId, updateTime),
    INDEX idx_generation_feedback_rating_update (rating, updateTime),
    CONSTRAINT chk_generation_feedback_rating CHECK (rating between 1 and 5)
) comment 'AI 生成结果用户反馈' collate = utf8mb4_unicode_ci;

-- 编排 DAG 检查点：崩溃后按节点续跑，而不是整任务重做
create table if not exists generation_orchestration_checkpoint
(
    id                bigint auto_increment primary key,
    taskId            varchar(128)                             not null comment 'generation task id',
    appId             bigint                                   not null comment 'application id',
    executionEpoch    bigint      default 0                    not null comment 'generation worker fencing epoch',
    requestHash       char(64)                                 not null comment 'sha-256 hash of the original request',
    status            varchar(32)                              not null comment 'running/completed/failed',
    runtimeState      varchar(32)                              not null comment 'agent runtime state',
    currentNode       varchar(128)                             null comment 'currently running DAG node',
    lastCompletedNode varchar(128)                             null comment 'last completed DAG node',
    checkpointVersion bigint      default 0                    not null comment 'monotonic checkpoint version',
    payloadJson       mediumtext                               not null comment 'versioned orchestration checkpoint payload',
    payloadBytes      int                                      not null comment 'serialized payload bytes',
    createTime        datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime        datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    isDelete          tinyint     default 0                    not null,
    UNIQUE KEY uk_generation_orchestration_task (taskId),
    INDEX idx_generation_orchestration_app (appId, isDelete, updateTime),
    INDEX idx_generation_orchestration_state (status, runtimeState, isDelete, updateTime),
    CONSTRAINT chk_generation_orchestration_checkpoint_version CHECK (checkpointVersion >= 0),
    CONSTRAINT chk_generation_orchestration_execution_epoch CHECK (executionEpoch >= 0),
    CONSTRAINT chk_generation_orchestration_payload_bytes CHECK (payloadBytes > 0)
) comment 'durable generation DAG checkpoint' collate = utf8mb4_unicode_ci;

-- 危险工具一次性审批：状态机由 CHECK 兜底，保证副作用不被重放
create table if not exists generation_tool_approval
(
    id                 bigint auto_increment primary key,
    approvalId         char(64)                                 not null comment 'target-bound approval id',
    taskId             varchar(128)                             not null comment 'generation task id',
    appId              bigint                                   not null comment 'application id',
    userId             bigint                                   not null comment 'application owner id',
    action             varchar(64)                              not null comment 'destructive tool action',
    requestJson        mediumtext                               not null comment 'normalized approval request',
    status             varchar(32) default 'pending'            not null comment 'pending/approved/rejected/executing/consumed/expired',
    requestedAt        datetime(6)                              not null,
    expiresAt          datetime(6)                              not null,
    decidedBy          bigint                                   null,
    decidedAt          datetime(6)                              null,
    consumedAt         datetime(6)                              null,
    executionStartedAt datetime(6)                              null comment 'current invocation execution start',
    executionResult    mediumtext                               null comment 'durable replayable tool result JSON',
    executionAttempt   int         default 0                    not null comment 'side-effect execution attempts',
    toolRequestId      varchar(128)                             null comment 'model tool invocation id',
    toolName           varchar(128)                             null comment 'model tool name',
    argumentsDigest    char(64)                                 null comment 'SHA-256 of tool arguments',
    checkpointJson     mediumtext                               null comment 'versioned runtime continuation checkpoint',
    version            bigint      default 0                    not null,
    createTime         datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime         datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_task_approval (taskId, approvalId),
    UNIQUE KEY uk_task_tool_request (taskId, toolRequestId),
    INDEX idx_approval_expiration (status, expiresAt, id),
    INDEX idx_approval_execution (status, executionStartedAt, id),
    INDEX idx_approval_app (appId, requestedAt),
    CONSTRAINT chk_generation_tool_approval_attempt CHECK (executionAttempt >= 0),
    -- 工具续跑四元组同生同灭，缺一个就无法安全恢复
    CONSTRAINT chk_generation_tool_approval_checkpoint CHECK (
        (toolRequestId is null and toolName is null and argumentsDigest is null and checkpointJson is null)
            or (toolRequestId is not null and toolName is not null
                and argumentsDigest is not null and checkpointJson is not null)
        ),
    CONSTRAINT chk_generation_tool_approval_expiry CHECK (expiresAt > requestedAt),
    -- 审批状态机：每个状态精确约束决策人/执行时间/结果的有无
    CONSTRAINT chk_generation_tool_approval_state CHECK (
        (status = 'pending' and decidedBy is null and decidedAt is null and consumedAt is null
            and executionStartedAt is null and executionResult is null and executionAttempt = 0)
            or (status = 'approved' and decidedBy is not null and decidedAt is not null and consumedAt is null
                and executionStartedAt is null and executionResult is null)
            or (status = 'rejected' and decidedBy is not null and decidedAt is not null and consumedAt is null
                and executionStartedAt is null and executionResult is null)
            or (status = 'executing' and decidedBy is not null and decidedAt is not null and consumedAt is null
                and executionStartedAt is not null and executionResult is null and executionAttempt > 0)
            or (status = 'consumed' and decidedBy is not null and decidedAt is not null and consumedAt is not null
                and executionStartedAt is not null and executionResult is not null and executionAttempt > 0)
            or (status = 'expired' and consumedAt is null
                and executionStartedAt is null and executionResult is null)
        ),
    CONSTRAINT chk_generation_tool_approval_status CHECK (
        status in ('pending', 'approved', 'rejected', 'executing', 'consumed', 'expired')
        )
) comment 'durable one-time AI tool approval' collate = utf8mb4_unicode_ci;

-- 语义记忆删除 outbox：删应用时与关系型事务同提交，异步补偿 Milvus
-- 运维不得手工把行标记为 completed，否则派生数据永久残留
create table if not exists semantic_memory_deletion_outbox
(
    id                bigint auto_increment primary key,
    operationId       char(64)                                 not null,
    operationType     varchar(32)                              not null,
    tenantId          bigint                                   not null,
    appId             bigint                                   not null,
    requestedByUserId bigint                                   not null,
    attempts          int         default 0                    not null,
    nextAttemptAt     datetime(6)                              not null,
    leaseOwner        varchar(128)                             null,
    leaseUntil        datetime(6)                              null,
    lastError         varchar(1000)                            null,
    completedAt       datetime(6)                              null,
    createTime        datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime        datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_semantic_memory_deletion_operation (operationId),
    UNIQUE KEY uk_semantic_memory_deletion_scope (operationType, tenantId, appId),
    INDEX idx_semantic_memory_deletion_claim (completedAt, nextAttemptAt, leaseUntil, id),
    CONSTRAINT chk_semantic_memory_deletion_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_semantic_memory_deletion_identity CHECK (
        tenantId > 0 and appId > 0 and requestedByUserId > 0
        ),
    CONSTRAINT chk_semantic_memory_deletion_lease CHECK (
        (leaseOwner is null and leaseUntil is null) or (leaseOwner is not null and leaseUntil is not null)
        ),
    CONSTRAINT chk_semantic_memory_deletion_type CHECK (operationType = 'DELETE_APPLICATION')
) comment 'durable outbox for deletion of derived Milvus semantic memory' collate = utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- AI 模型配置与发布治理
-- -----------------------------------------------------------------------------

-- AI 模型配置
-- 凭据以信封加密后存 secretRef，明文 apiKey 列已废弃，不再重建。
-- 同类型允许多个启用模型，运行时按 sortOrder 组成故障转移池（详见文末说明 1）。
create table if not exists ai_model
(
    id               bigint auto_increment comment 'id' primary key,
    modelName        varchar(128)                       not null comment '模型显示名称',
    provider         varchar(64)                        not null comment '模型提供商：deepseek/openai/custom',
    modelId          varchar(128)                       not null comment '模型标识符，如 deepseek-v4-flash',
    description      varchar(512)                       null comment '模型描述',
    baseUrl          varchar(512)                       not null comment 'API 基础地址',
    secretRef        varchar(4096)                      null comment 'Envelope-encrypted API credential reference',
    secretFingerprint char(64)                          null comment 'Stable HMAC-SHA256 credential fingerprint',
    secretKeyId      varchar(64)                        null comment 'Key-encryption-key identifier',
    maxTokens        int      default 8192              not null comment '最大 token 数',
    temperature      double   default 0.7               null comment '温度参数',
    isEnabled        tinyint  default 1                 not null comment '是否启用：0-禁用 1-启用',
    modelType        varchar(32) default 'chat'         not null comment '模型类型：chat/reasoning/routing',
    supportsThinking tinyint  default 0                 not null comment '是否支持 thinking 模式：0-不支持 1-支持',
    sortOrder        int      default 0                 not null comment '排序权重',
    configJson       text                               null comment '扩展配置 JSON',
    userId           bigint                             not null comment '创建用户id',
    editTime         datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime       datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint  default 0                 not null comment '是否删除',
    -- 生成列把软删除行排除在唯一键之外，使删除后的身份可以被重新登记
    activeProvider   varchar(64) generated always as
        (case when isDelete = 0 then provider else null end) stored
        comment '未删除模型提供商唯一键',
    activeModelId    varchar(128) generated always as
        (case when isDelete = 0 then modelId else null end) stored
        comment '未删除模型标识唯一键',
    UNIQUE KEY uk_active_provider_model (activeProvider, activeModelId),
    INDEX idx_isEnabled (isEnabled),
    INDEX idx_userId (userId),
    INDEX idx_modelType (modelType),
    INDEX idx_modelType_enabled (modelType, isEnabled, isDelete),
    -- 运行时故障转移池：按类型取启用模型并以 sortOrder 排序
    INDEX idx_ai_model_runtime_pool (modelType, isEnabled, isDelete, sortOrder, id)
) comment 'AI 模型配置' collate = utf8mb4_unicode_ci;

-- 当前生效的提示词发布指针（stable + 可选灰度）
create table if not exists ai_prompt_release
(
    promptKey        varchar(64)                              not null primary key,
    stableVersion    varchar(32)                              not null,
    canaryVersion    varchar(32)                              null,
    canaryPercentage tinyint     default 0                    not null,
    revision         bigint                                   not null,
    updatedBy        bigint                                   not null,
    changeNote       varchar(512)                             not null,
    createTime       datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime       datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    INDEX idx_ai_prompt_release_revision (revision),
    -- 灰度比例与灰度版本必须一致；灰度版本不得与 stable 相同（否则灰度无意义）
    CONSTRAINT chk_ai_prompt_release_canary CHECK (
        (canaryPercentage = 0 and canaryVersion is null)
            or (canaryPercentage between 1 and 100 and canaryVersion is not null
                and canaryVersion <> stableVersion)
        ),
    CONSTRAINT chk_ai_prompt_release_identifiers CHECK (
        char_length(trim(promptKey)) between 1 and 64
            and char_length(trim(stableVersion)) between 1 and 32
            and (canaryVersion is null or char_length(trim(canaryVersion)) between 1 and 32)
        ),
    CONSTRAINT chk_ai_prompt_release_note CHECK (char_length(trim(changeNote)) between 1 and 512),
    CONSTRAINT chk_ai_prompt_release_operator CHECK (updatedBy > 0),
    CONSTRAINT chk_ai_prompt_release_percentage CHECK (canaryPercentage between 0 and 100),
    CONSTRAINT chk_ai_prompt_release_revision CHECK (revision > 0)
) comment 'current runtime AI prompt release pointers' collate = utf8mb4_unicode_ci;

-- 提示词发布包头：单行表（id 恒为 1），用于原子推进整包版本
create table if not exists ai_prompt_release_bundle
(
    id         tinyint                                  not null primary key,
    revision   bigint      default 0                    not null,
    updatedBy  bigint                                   null,
    updateTime datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_prompt_release_bundle_id CHECK (id = 1),
    CONSTRAINT chk_ai_prompt_release_bundle_revision CHECK (revision >= 0)
) comment 'atomic AI prompt release bundle head' collate = utf8mb4_unicode_ci;

-- 提示词发布历史：只追加不修改，回滚必须指向来源 revision
create table if not exists ai_prompt_release_history
(
    revision         bigint                                   not null primary key,
    promptKey        varchar(64)                              not null,
    stableVersion    varchar(32)                              not null,
    canaryVersion    varchar(32)                              null,
    canaryPercentage tinyint     default 0                    not null,
    action           varchar(16)                              not null,
    sourceRevision   bigint                                   null,
    updatedBy        bigint                                   not null,
    changeNote       varchar(512)                             not null,
    evidenceId       char(36)                                 null,
    createTime       datetime(6) default CURRENT_TIMESTAMP(6) not null,
    INDEX idx_ai_prompt_release_history_key (promptKey, revision),
    CONSTRAINT chk_ai_prompt_release_history_action CHECK (action in ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT chk_ai_prompt_release_history_canary CHECK (
        (canaryPercentage = 0 and canaryVersion is null)
            or (canaryPercentage between 1 and 100 and canaryVersion is not null
                and canaryVersion <> stableVersion)
        ),
    CONSTRAINT chk_ai_prompt_release_history_identifiers CHECK (
        char_length(trim(promptKey)) between 1 and 64
            and char_length(trim(stableVersion)) between 1 and 32
            and (canaryVersion is null or char_length(trim(canaryVersion)) between 1 and 32)
        ),
    CONSTRAINT chk_ai_prompt_release_history_note CHECK (char_length(trim(changeNote)) between 1 and 512),
    CONSTRAINT chk_ai_prompt_release_history_operator CHECK (updatedBy > 0),
    CONSTRAINT chk_ai_prompt_release_history_percentage CHECK (canaryPercentage between 0 and 100),
    -- PUBLISH 不带来源；ROLLBACK 必须带正数来源 revision
    CONSTRAINT chk_ai_prompt_release_history_source CHECK (
        (action = 'PUBLISH' and sourceRevision is null)
            or (action = 'ROLLBACK' and sourceRevision is not null and sourceRevision > 0)
        )
) comment 'immutable AI prompt release audit history' collate = utf8mb4_unicode_ci;

-- 发布基准证据：签名后不可变，作为启用模型/发布提示词的门禁输入
create table if not exists generation_benchmark_evidence
(
    id                            bigint auto_increment primary key,
    evidenceId                    char(36)                                 not null,
    subjectType                   varchar(32)                              not null,
    subjectKey                    varchar(128)                             not null,
    candidateFingerprint          char(64)                                 not null,
    signatureVersion              smallint    default 1                    not null,
    candidatePhysicalRequestCount bigint      default 0                    not null,
    datasetFingerprint            char(64)                                 not null,
    graderFingerprint             varchar(128)                             not null,
    runtimeConfigFingerprint      char(64)                                 not null,
    gitCommit                     varchar(64)                              not null,
    modelFingerprint              char(64)                                 not null,
    promptBundleFingerprint       char(64)                                 not null,
    reportSha256                  char(64)                                 not null,
    reportJson                    mediumtext                               not null,
    passed                        tinyint                                  not null,
    violationsJson                mediumtext                               not null,
    signature                     char(64)                                 not null,
    evaluatedAt                   datetime(6)                              not null,
    expiresAt                     datetime(6)                              not null,
    createTime                    datetime(6) default CURRENT_TIMESTAMP(6) not null,
    isDelete                      tinyint     default 0                    not null,
    UNIQUE KEY uk_generation_benchmark_evidence_id (evidenceId),
    INDEX idx_generation_benchmark_evidence_subject (subjectType, subjectKey, candidateFingerprint, passed, expiresAt),
    INDEX idx_generation_benchmark_evidence_expiry (expiresAt, isDelete),
    -- v2 签名要求模型类证据必须实测过物理请求，防止「零调用也判通过」
    CONSTRAINT chk_generation_benchmark_evidence_attestation CHECK (
        (signatureVersion = 1 and candidatePhysicalRequestCount = 0)
            or (signatureVersion = 2
                and ((subjectType = 'AI_MODEL_ENABLE' and candidatePhysicalRequestCount > 0)
                    or (subjectType = 'PROMPT_RELEASE' and candidatePhysicalRequestCount = 0)))
        ),
    CONSTRAINT chk_generation_benchmark_evidence_passed CHECK (passed in (0, 1)),
    CONSTRAINT chk_generation_benchmark_evidence_subject CHECK (
        subjectType in ('PROMPT_RELEASE', 'AI_MODEL_ENABLE')
        ),
    CONSTRAINT chk_generation_benchmark_evidence_window CHECK (expiresAt > evaluatedAt)
) comment 'signed immutable AI release benchmark evidence' collate = utf8mb4_unicode_ci;

-- 证据使用审计：记录哪次发布消费了哪份证据，只追加不修改
create table if not exists ai_release_audit
(
    id                   bigint auto_increment primary key,
    auditId              char(36)                                 not null,
    evidenceId           char(36)                                 not null,
    subjectType          varchar(32)                              not null,
    subjectKey           varchar(128)                             not null,
    candidateFingerprint char(64)                                 not null,
    action               varchar(32)                              not null,
    operatorUserId       bigint                                   not null,
    releaseReference     varchar(128)                             not null,
    createTime           datetime(6) default CURRENT_TIMESTAMP(6) not null,
    UNIQUE KEY uk_ai_release_audit_id (auditId),
    INDEX idx_ai_release_audit_evidence (evidenceId, createTime),
    INDEX idx_ai_release_audit_subject (subjectType, subjectKey, createTime),
    CONSTRAINT chk_ai_release_audit_operator CHECK (operatorUserId > 0)
) comment 'immutable AI release evidence usage audit' collate = utf8mb4_unicode_ci;

-- 发布协调锁：靠数据库行锁串行化跨实例发布，不可替换为进程内锁
create table if not exists ai_release_coordination_lock
(
    lockName   varchar(64)                              not null primary key,
    createTime datetime(6) default CURRENT_TIMESTAMP(6) not null,
    updateTime datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_release_coordination_lock_name CHECK (char_length(trim(lockName)) between 1 and 64)
) comment 'AI 发布事务协调锁' collate = utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 初始化数据：应用启动即依赖这两行，缺失会导致发布链路直接失败
-- -----------------------------------------------------------------------------

-- 全局发布锁行：发布流程 SELECT ... FOR UPDATE 该行来串行化
insert ignore into ai_release_coordination_lock (lockName) values ('global');

-- 提示词发布包头初始行：revision 从 0 起步
insert ignore into ai_prompt_release_bundle (id, revision, updatedBy) values (1, 0, null);

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 与迁移链的两处有意差异
-- =============================================================================
-- 1. ai_model 移除 uk_active_model_type 单活唯一键
--    迁移链中 V20260716_4__ai_model_fallback_pool.sql 本意就是删掉该唯一键并加上
--    idx_ai_model_runtime_pool，但它的版本号 20260716.4 低于基线 20260716.5，
--    Flyway 对新库只应用「版本号高于基线」的迁移，因此该文件在新库上永远不会执行。
--    两个文件同属提交 9cebda9，属于版本号排序失误，不是有意回退。
--    保留该唯一键的实际后果：同一 modelType 只能有一个启用模型，
--    而 AiModelMapper.selectEnabled 返回按 sortOrder 排序的列表、
--    DefaultAiModelRuntimeService.listRunnableModelsByType 依赖多候选做熔断转移，
--    即插入第二个启用模型会直接报 Duplicate entry '...' for key 'uk_active_model_type'。
--    代码中没有任何位置引用 activeModelType，故此处按运行时语义采用池化索引。
--
-- 2. app 补充 devServerPort 列
--    该列在 sql/migrations/ 下的任何迁移中都不存在，仅出现在 sql/create_table.sql，
--    且用的是 MariaDB 专有语法 add column if not exists（MySQL 8 上直接语法报错）。
--    但 AppMapper 有三处强依赖：selectActiveById 选取该列、
--    updateActiveDevServerPort 写该列、selectDevServerTarget 选取该列，
--    App 实体与 AppVO 也都声明了该字段。缺列会让 Dev Server 预览链路在运行时报
--    Unknown column 'devServerPort'，因此必须补上。
--
-- 除以上两点外，本文件结构与 Flyway 回放结果逐列、逐索引、逐约束一致。
-- =============================================================================
