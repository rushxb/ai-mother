-- Bind prompt/model release mutations to immutable benchmark evidence.
SET @prompt_evidence_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_prompt_release_history'
      AND column_name = 'evidenceId'
);
SET @prompt_evidence_column_sql = IF(
    @prompt_evidence_column_exists = 0,
    'ALTER TABLE ai_prompt_release_history ADD COLUMN evidenceId char(36) null AFTER changeNote',
    'SELECT 1'
);
PREPARE prompt_evidence_column_statement FROM @prompt_evidence_column_sql;
EXECUTE prompt_evidence_column_statement;
DEALLOCATE PREPARE prompt_evidence_column_statement;

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
