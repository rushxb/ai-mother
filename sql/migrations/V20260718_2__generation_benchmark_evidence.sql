create table if not exists generation_benchmark_evidence
(
    id                       bigint auto_increment primary key,
    evidenceId               char(36)                           not null,
    subjectType              varchar(32)                        not null,
    subjectKey               varchar(128)                       not null,
    candidateFingerprint     char(64)                           not null,
    datasetFingerprint       char(64)                           not null,
    graderFingerprint        varchar(128)                       not null,
    runtimeConfigFingerprint char(64)                           not null,
    gitCommit                varchar(64)                        not null,
    modelFingerprint         char(64)                           not null,
    promptBundleFingerprint  char(64)                           not null,
    reportSha256             char(64)                           not null,
    reportJson               mediumtext                         not null,
    passed                   tinyint                            not null,
    violationsJson           mediumtext                         not null,
    signature                char(64)                           not null,
    evaluatedAt              datetime(6)                        not null,
    expiresAt                datetime(6)                        not null,
    createTime               datetime(6) default CURRENT_TIMESTAMP(6) not null,
    isDelete                 tinyint     default 0              not null,
    unique key uk_generation_benchmark_evidence_id (evidenceId),
    index idx_generation_benchmark_evidence_subject
        (subjectType, subjectKey, candidateFingerprint, passed, expiresAt),
    index idx_generation_benchmark_evidence_expiry (expiresAt, isDelete),
    constraint chk_generation_benchmark_evidence_subject
        check (subjectType in ('PROMPT_RELEASE', 'AI_MODEL_ENABLE')),
    constraint chk_generation_benchmark_evidence_passed check (passed in (0, 1)),
    constraint chk_generation_benchmark_evidence_window check (expiresAt > evaluatedAt)
) comment 'signed immutable AI release benchmark evidence' collate = utf8mb4_unicode_ci;
