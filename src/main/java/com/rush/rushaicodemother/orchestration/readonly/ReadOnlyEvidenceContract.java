package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;

/** 三种只读操作各自唯一的上下文与文件依据合同。 */
enum ReadOnlyEvidenceContract {

    EXPLAIN(IntentOperationType.EXPLAIN, false, true, ReadOnlyAnalysisStatus.NO_PROJECT_CONTEXT),
    AUDIT(IntentOperationType.AUDIT, false, true, ReadOnlyAnalysisStatus.NOT_AUDITABLE),
    PLAN(IntentOperationType.PLAN, true, false, null);

    private final IntentOperationType operationType;
    private final boolean modelAllowedWithoutRepository;
    private final boolean groundedReferenceRequired;
    private final ReadOnlyAnalysisStatus emptyRepositoryStatus;

    ReadOnlyEvidenceContract(IntentOperationType operationType,
                             boolean modelAllowedWithoutRepository,
                             boolean groundedReferenceRequired,
                             ReadOnlyAnalysisStatus emptyRepositoryStatus) {
        this.operationType = operationType;
        this.modelAllowedWithoutRepository = modelAllowedWithoutRepository;
        this.groundedReferenceRequired = groundedReferenceRequired;
        this.emptyRepositoryStatus = emptyRepositoryStatus;
    }

    static ReadOnlyEvidenceContract resolve(IntentOperationType operationType) {
        for (ReadOnlyEvidenceContract contract : values()) {
            if (contract.operationType == operationType) {
                return contract;
            }
        }
        throw new IllegalArgumentException("只读分析仅接受 EXPLAIN、AUDIT 或 PLAN 操作");
    }

    boolean modelAllowedWithoutRepository() {
        return modelAllowedWithoutRepository;
    }

    boolean groundedReferenceRequired() {
        return groundedReferenceRequired;
    }

    ReadOnlyAnalysisStatus emptyRepositoryStatus() {
        return emptyRepositoryStatus;
    }
}
