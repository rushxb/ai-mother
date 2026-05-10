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
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;

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
    generatingStage varchar(32)                     null comment '当前生成阶段：create / update',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;

-- 已有库升级可执行以下语句
alter table app add column if not exists isGenerating tinyint default 0 not null comment '是否正在生成';
alter table app add column if not exists generatingMessage mediumtext null comment '当前生成中的 AI 响应快照';
alter table app add column if not exists generatingStage varchar(32) null comment '当前生成阶段：create / update';

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
    dbEngine           varchar(64) default 'SqlLite'      not null comment '数据库引擎',
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
    message     text                               not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),                       -- 提升基于应用的查询性能
    INDEX idx_createTime (createTime),             -- 提升基于时间的查询性能
    INDEX idx_appId_createTime (appId, createTime) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_unicode_ci;
