CREATE TABLE generation_model_prompt_selection
(
    id            bigint auto_increment primary key,
    callId        varchar(36)                         not null,
    taskId        varchar(128)                        not null,
    promptKey     varchar(64)                         not null,
    promptVersion varchar(32)                         not null,
    channel       varchar(16)                         not null,
    contentHash   char(64)                            not null,
    bundleId      char(64)                            not null,
    createTime    datetime(6) default CURRENT_TIMESTAMP(6) not null,
    isDelete      tinyint     default 0               not null,
    CONSTRAINT chk_generation_model_prompt_selection_channel
        CHECK (channel IN ('stable', 'canary', 'archived')),
    UNIQUE KEY uk_model_prompt_selection_call_key_version
        (callId, promptKey, promptVersion),
    INDEX idx_model_prompt_selection_task_key
        (taskId, promptKey, contentHash),
    INDEX idx_model_prompt_selection_rollout
        (promptKey, channel, contentHash, createTime)
) comment '模型调用实际 Prompt 版本事实' collate = utf8mb4_unicode_ci;
