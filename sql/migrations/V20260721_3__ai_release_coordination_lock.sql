-- 通过数据库事务行锁串行化会改变 AI 发布身份的操作。
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
