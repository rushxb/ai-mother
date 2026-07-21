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
