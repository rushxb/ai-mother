-- Immutable Flyway baseline for new databases at schema version 20260716.5.
-- Existing non-empty databases must opt in to baseline-on-migrate only after schema verification.

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

-- 用户积分流水表
create table if not exists user_credit_transaction
(
    id            bigint auto_increment comment 'id' primary key,
    userId        bigint                             not null comment '用户id',
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
    generationLeaseUntil datetime(6)                 null comment '生成状态租约到期时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId),           -- 提升基于用户 ID 的查询性能
    INDEX idx_generation_lease (isGenerating, generationLeaseUntil),
    CONSTRAINT chk_app_generation_state_ownership CHECK (
        (isGenerating = 0 AND generatingTaskId IS NULL AND generationLeaseUntil IS NULL)
        OR (isGenerating = 1 AND generatingTaskId IS NOT NULL AND generationLeaseUntil IS NOT NULL)
    )
) comment '应用' collate = utf8mb4_unicode_ci;

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
        orchestrationMode       varchar(64)                        null comment '编排模式',
        route                   varchar(64)                        null comment '运行时路由',
        submittedAt             datetime(6)                        not null comment '任务提交时间',
        deadlineAt              datetime(6)                        null comment '任务绝对截止时间',
        cancellationRequested   tinyint     default 0              not null comment '是否请求取消',
        cancellationReason      varchar(512)                       null comment '取消原因',
        leaseOwner              varchar(128)                       null comment 'worker 租约所有者',
        leaseUntil              datetime(6)                        null comment 'worker 租约到期时间',
        heartbeatAt             datetime(6)                        null comment 'worker 最近心跳时间',
        attempt                 int         default 0              not null comment 'worker 领取次数',
        version                 bigint      default 0              not null comment '运行时乐观锁版本',
        startTime               datetime default CURRENT_TIMESTAMP not null comment '开始时间',
        endTime                 datetime                           null comment '结束时间',
        durationMs              bigint                             null comment '耗时毫秒',
        errorMessage            text                               null comment '错误信息',
        memorySummary           mediumtext                         null comment 'AI 可读的生成记忆摘要',
        memoryIndexedAt         datetime(6)                        null comment '语义记忆成功写入 Milvus 的时间',
        memoryIndexAttempts     int         default 0              not null comment '语义记忆索引尝试次数',
        memoryIndexError        varchar(1000)                      null comment '语义记忆最近索引错误',
        totalTokens             bigint   default 0                 not null comment '任务累计 token 数',
        creditCost              bigint   default 0                 not null comment '任务消耗积分',
        creditCharged           tinyint  default 0                 not null comment '是否已结算积分',
        createTime              datetime default CURRENT_TIMESTAMP not null comment '创建时间',
        updateTime              datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
        isDelete                tinyint  default 0                 not null comment '是否删除',
        UNIQUE KEY uk_taskId (taskId),
        INDEX idx_appId_createTime (appId, createTime),
        INDEX idx_userId_createTime (userId, createTime),
        INDEX idx_status_createTime (status, createTime),
        INDEX idx_route_success_duration (route, status, isDelete, endTime, id),
        INDEX idx_runtime_lease (status, leaseUntil, isDelete),
        INDEX idx_app_runtime_status (appId, status, submittedAt),
        INDEX idx_user_runtime_status (userId, status, isDelete, submittedAt),
        INDEX idx_memory_outbox (memoryIndexedAt, memoryIndexAttempts, status, isDelete, endTime)
    ) comment 'AI 生成任务' collate = utf8mb4_unicode_ci;

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
    promptTokens     int                                null comment '输入 token 数',
    completionTokens int                                null comment '输出 token 数',
    totalTokens      int                                null comment '总 token 数',
    latencyMs        bigint                             null comment '模型调用耗时毫秒',
    finishReason     varchar(64)                        null comment '结束原因',
    usageSource      varchar(32) default 'OFFICIAL'     not null comment 'token 来源：OFFICIAL/ESTIMATED',
    rawMetadataJson  mediumtext                         null comment '原始元数据 JSON',
        createTime       datetime default CURRENT_TIMESTAMP not null comment '创建时间',
        isDelete         tinyint  default 0                 not null comment '是否删除',
        UNIQUE KEY uk_callId (callId),
        INDEX idx_taskId_createTime (taskId, createTime),
    INDEX idx_model_createTime (model, createTime),
    INDEX idx_appId_createTime (appId, createTime)
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
    apiKey         varchar(2048)                      null comment 'API 密钥',
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

-- 并发单活约束、API Key 列扩容和软删除身份唯一约束请执行版本化迁移：
-- sql/migrations/V20260713__ai_model_write_integrity.sql
-- sql/migrations/V20260714_2__ai_model_soft_delete_identity.sql
