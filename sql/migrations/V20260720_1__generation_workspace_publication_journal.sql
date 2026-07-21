ALTER TABLE generation_task
    ADD COLUMN publicationStatus varchar(32) null comment 'prepared/filesystem_activated/committed/rollback_required/rolled_back/superseded' AFTER creditCharged,
    ADD COLUMN publicationCodeGenType varchar(32) null comment 'published workspace code generation type' AFTER publicationStatus,
    ADD COLUMN publicationExecutionEpoch bigint null comment 'publication fencing epoch' AFTER publicationCodeGenType,
    ADD COLUMN publicationPublishedAt datetime(6) null comment 'stable publication pointer timestamp' AFTER publicationExecutionEpoch,
    ADD COLUMN publicationAttempts int default 0 not null comment 'publication reconciliation attempts' AFTER publicationPublishedAt,
    ADD COLUMN publicationVersion bigint default 0 not null comment 'publication journal optimistic version' AFTER publicationAttempts,
    ADD COLUMN publicationError varchar(1024) null comment 'latest publication reconciliation failure' AFTER publicationVersion,
    ADD COLUMN publicationReconcileAfter datetime(6) null comment 'next eligible publication reconciliation time' AFTER publicationError,
    ADD COLUMN publicationCommittedAt datetime(6) null comment 'filesystem and metadata publication commit time' AFTER publicationReconcileAfter,
    ADD INDEX idx_generation_task_publication_reconcile (publicationStatus, publicationReconcileAfter, id),
    ADD CONSTRAINT chk_generation_task_publication_attempts CHECK (publicationAttempts >= 0),
    ADD CONSTRAINT chk_generation_task_publication_version CHECK (publicationVersion >= 0),
    ADD CONSTRAINT chk_generation_task_publication_state CHECK (
        (publicationStatus IS NULL
            AND publicationCodeGenType IS NULL
            AND publicationExecutionEpoch IS NULL
            AND publicationPublishedAt IS NULL
            AND publicationCommittedAt IS NULL)
        OR (publicationStatus IN (
                'prepared', 'filesystem_activated', 'committed',
                'rollback_required', 'rolled_back', 'superseded')
            AND publicationCodeGenType IS NOT NULL
            AND publicationExecutionEpoch IS NOT NULL
            AND publicationExecutionEpoch > 0
            AND publicationPublishedAt IS NOT NULL
            AND ((publicationStatus = 'committed' AND publicationCommittedAt IS NOT NULL)
                OR (publicationStatus <> 'committed' AND publicationCommittedAt IS NULL)))
    );
