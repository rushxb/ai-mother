CREATE TABLE ai_prompt_canary_assessment
(
    id                              bigint auto_increment primary key,
    assessmentId                    char(36)                            not null,
    promptKey                       varchar(64)                         not null,
    releaseRevision                 bigint                              not null,
    bundleRevision                  bigint                              not null,
    bundleId                        char(64)                            not null,
    stableVersion                   varchar(32)                         not null,
    stableContentHash               char(64)                            not null,
    canaryVersion                   varchar(32)                         not null,
    canaryContentHash               char(64)                            not null,
    windowStart                     datetime(6)                         not null,
    windowEnd                       datetime(6)                         not null,
    decision                        varchar(32)                         not null,
    stableTaskCount                 bigint                              not null,
    canaryTaskCount                 bigint                              not null,
    ambiguousTaskCount              bigint                              not null,
    invalidAttributionTaskCount     bigint                              not null,
    violationsJson                  text                                not null,
    evidenceJson                    mediumtext                          not null,
    evidenceHash                    char(64)                            not null,
    evaluatedAt                     datetime(6)                         not null,
    createTime                      datetime(6) default CURRENT_TIMESTAMP(6) not null,
    UNIQUE KEY uk_prompt_canary_assessment_id (assessmentId),
    INDEX idx_prompt_canary_assessment_release
        (promptKey, releaseRevision, evaluatedAt),
    INDEX idx_prompt_canary_assessment_decision
        (decision, evaluatedAt),
    CONSTRAINT chk_prompt_canary_assessment_revision
        CHECK (releaseRevision > 0 AND bundleRevision >= releaseRevision),
    CONSTRAINT chk_prompt_canary_assessment_window
        CHECK (windowEnd > windowStart),
    CONSTRAINT chk_prompt_canary_assessment_counts
        CHECK (stableTaskCount >= 0 AND canaryTaskCount >= 0
            AND ambiguousTaskCount >= 0 AND invalidAttributionTaskCount >= 0),
    CONSTRAINT chk_prompt_canary_assessment_decision
        CHECK (decision IN ('OBSERVING', 'HOLD', 'PROMOTABLE',
                            'ROLLBACK_REQUIRED', 'INVALID')),
    CONSTRAINT chk_prompt_canary_assessment_versions
        CHECK (stableVersion <> canaryVersion AND stableContentHash <> canaryContentHash)
) comment 'Prompt stable canary immutable production assessment' collate = utf8mb4_unicode_ci;
