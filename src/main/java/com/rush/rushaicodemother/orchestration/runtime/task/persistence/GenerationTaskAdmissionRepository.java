package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import java.util.Optional;

/** Serializes admission decisions for one user and returns the current durable outstanding load. */
public interface GenerationTaskAdmissionRepository {

    int lockUserAndCountNonTerminalTasks(Long userId);

    Optional<GenerationTaskIdempotencyRecord> findByIdempotencyKey(Long tenantId,
                                                                   Long userId,
                                                                   Long appId,
                                                                   String idempotencyKeyHash);
}
