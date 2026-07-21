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
