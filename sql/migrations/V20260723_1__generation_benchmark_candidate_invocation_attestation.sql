alter table generation_benchmark_evidence
    add column signatureVersion smallint not null default 1 after candidateFingerprint,
    add column candidatePhysicalRequestCount bigint not null default 0 after signatureVersion,
    add constraint chk_generation_benchmark_evidence_attestation
        check (
            (signatureVersion = 1 and candidatePhysicalRequestCount = 0)
            or (signatureVersion = 2 and (
                (subjectType = 'AI_MODEL_ENABLE' and candidatePhysicalRequestCount > 0)
                or (subjectType = 'PROMPT_RELEASE' and candidatePhysicalRequestCount = 0)
            ))
        );
