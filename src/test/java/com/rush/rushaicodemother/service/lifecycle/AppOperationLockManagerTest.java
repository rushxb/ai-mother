package com.rush.rushaicodemother.service.lifecycle;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppOperationLockManagerTest {

    private final AppOperationLockManager lockManager = new AppOperationLockManager();

    @Test
    void shouldRejectInvalidApplicationIdAndOperation() {
        BusinessException invalidId = assertThrows(
                BusinessException.class,
                () -> lockManager.execute(0L, () -> true)
        );
        BusinessException missingOperation = assertThrows(
                BusinessException.class,
                () -> lockManager.execute(1L, (Runnable) null)
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), invalidId.getCode());
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), missingOperation.getCode());
    }

    @Test
    void shouldSerializeOperationsForSameApplication() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondAttempted = new CountDownLatch(1);
            AtomicInteger activeOperations = new AtomicInteger();
            AtomicInteger maximumConcurrency = new AtomicInteger();
            try {
                Future<?> first = executor.submit(() -> lockManager.execute(11L, () -> {
                    int active = activeOperations.incrementAndGet();
                    maximumConcurrency.accumulateAndGet(active, Math::max);
                    firstEntered.countDown();
                    awaitLatch(releaseFirst);
                    activeOperations.decrementAndGet();
                }));
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

                Future<?> second = executor.submit(() -> {
                    secondAttempted.countDown();
                    lockManager.execute(11L, () -> {
                        int active = activeOperations.incrementAndGet();
                        maximumConcurrency.accumulateAndGet(active, Math::max);
                        activeOperations.decrementAndGet();
                    });
                });
                assertTrue(secondAttempted.await(1, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertEquals(1, activeOperations.get());

                releaseFirst.countDown();
                first.get(1, TimeUnit.SECONDS);
                second.get(1, TimeUnit.SECONDS);
                assertEquals(1, maximumConcurrency.get());
            } finally {
                releaseFirst.countDown();
                executor.shutdownNow();
            }
        });
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待锁测试信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("锁测试线程被中断", exception);
        }
    }
}
