-- 物理 provider 请求前必须先持久化 STARTED 账本；终态再通过 callId CAS 完成。
-- V20260717_5 已在所有受支持的迁移路径上建立此命名约束，因此这里可确定性替换。
ALTER TABLE generation_model_call
    DROP CHECK chk_generation_model_call_status;

ALTER TABLE generation_model_call
    MODIFY COLUMN callStatus varchar(32) default 'SUCCESS' not null
        comment 'STARTED/SUCCESS/ERROR',
    ADD CONSTRAINT chk_generation_model_call_status
        CHECK (callStatus IN ('STARTED', 'SUCCESS', 'ERROR'));
