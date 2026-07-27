package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import java.util.Optional;

/** 序列化一个用户的准入决策并返回当前持久的未完成负载。 */
public interface GenerationTaskAdmissionRepository {

    int lockUserAndCountNonTerminalTasks(Long userId);

    Optional<GenerationTaskIdempotencyRecord> findByIdempotencyKey(Long tenantId,
                                                                   Long userId,
                                                                   Long appId,
                                                                   String idempotencyKeyHash);
}
