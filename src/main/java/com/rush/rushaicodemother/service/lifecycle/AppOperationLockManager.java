package com.rush.rushaicodemother.service.lifecycle;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 应用级本地操作锁。
 *
 * <p>部署、复制和删除都会切换应用关联的本地产物。通过共享条带锁串行化同一应用的
 * 生命周期操作，避免各模块维护彼此不可见的锁而产生目录竞争。</p>
 */
@Component
public class AppOperationLockManager {

    private static final int LOCK_STRIPES = 64;

    private final ReentrantLock[] locks = createLocks();

    /** 在应用锁内执行并返回结果。 */
    public <T> T execute(Long appId, Supplier<T> operation) {
        validateArguments(appId, operation);
        ReentrantLock lock = lockFor(appId);
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    /** 在应用锁内执行无返回值操作。 */
    public void execute(Long appId, Runnable operation) {
        validateArguments(appId, operation);
        execute(appId, () -> {
            operation.run();
            return null;
        });
    }

    private void validateArguments(Long appId, Object operation) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        ThrowUtils.throwIf(operation == null, ErrorCode.PARAMS_ERROR, "应用操作不能为空");
    }

    private ReentrantLock lockFor(Long appId) {
        return locks[Math.floorMod(Long.hashCode(appId), locks.length)];
    }

    private ReentrantLock[] createLocks() {
        ReentrantLock[] createdLocks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < createdLocks.length; index++) {
            createdLocks[index] = new ReentrantLock();
        }
        return createdLocks;
    }
}
