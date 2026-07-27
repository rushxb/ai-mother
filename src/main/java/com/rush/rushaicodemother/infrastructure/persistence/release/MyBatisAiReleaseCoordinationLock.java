package com.rush.rushaicodemother.infrastructure.persistence.release;

import com.rush.rushaicodemother.mapper.AiReleaseCoordinationMapper;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 使用关系库单例行锁实现跨实例 AI 发布协调。 */
@Repository
@RequiredArgsConstructor
public class MyBatisAiReleaseCoordinationLock implements AiReleaseCoordinationLock {

    private static final String GLOBAL_LOCK_NAME = "global";

    private final AiReleaseCoordinationMapper mapper;

    @Override
    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("AI 发布协调锁必须在可写事务中获取");
        }
        String lockedName = mapper.lockByName(GLOBAL_LOCK_NAME);
        if (!GLOBAL_LOCK_NAME.equals(lockedName)) {
            throw new IllegalStateException("AI 发布协调锁记录不存在");
        }
    }
}
