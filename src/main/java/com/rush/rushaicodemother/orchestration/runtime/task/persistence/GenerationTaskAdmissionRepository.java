package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskAdmissionSnapshot;

import java.util.Optional;

/** 串行化租户和用户准入决策，并读取当前容量与预算事实。 */
public interface GenerationTaskAdmissionRepository {

    GenerationTaskAdmissionSnapshot lockScopeAndMeasure(Long tenantId, Long userId);

    Optional<GenerationTaskIdempotencyRecord> findByIdempotencyKey(Long tenantId,
                                                                   Long userId,
                                                                   Long appId,
                                                                   String idempotencyKeyHash);
}
