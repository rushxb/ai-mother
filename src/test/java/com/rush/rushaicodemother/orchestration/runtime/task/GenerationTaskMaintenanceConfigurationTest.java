package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.BackgroundJobSchedulingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationTaskMaintenanceConfigurationTest {

    @Test
    void blockedRecoveryMustNotDelayLeaseHeartbeat() {
        GenerationTaskLeaseCoordinator coordinator = mock(GenerationTaskLeaseCoordinator.class);
        GenerationTaskRecoveryService recoveryService = mock(GenerationTaskRecoveryService.class);
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setLeaseDuration(Duration.ofSeconds(2));
        properties.setHeartbeatInterval(Duration.ofMillis(25));
        properties.setRecoveryScanInterval(Duration.ofMillis(25));
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        CountDownLatch secondHeartbeat = new CountDownLatch(1);
        AtomicInteger heartbeatCount = new AtomicInteger();

        doAnswer(invocation -> {
            if (heartbeatCount.incrementAndGet() >= 2) {
                secondHeartbeat.countDown();
            }
            return null;
        }).when(coordinator).heartbeatTrackedTasks();
        when(recoveryService.recoverExpiredTasks()).thenAnswer(invocation -> {
            recoveryStarted.countDown();
            await(releaseRecovery, Duration.ofSeconds(2));
            return 0;
        });

        try {
            new ApplicationContextRunner()
                    .withPropertyValues(
                            "app.background-jobs.enabled=true",
                            "app.background-jobs.scheduling.default-pool-size=3",
                            "app.background-jobs.scheduling.generation-task-maintenance-pool-size=4")
                    .withBean("generationTaskLeaseProperties",
                            GenerationTaskLeaseProperties.class, () -> properties)
                    .withBean(GenerationTaskLeaseCoordinator.class, () -> coordinator)
                    .withBean(GenerationTaskRecoveryService.class, () -> recoveryService)
                    .withUserConfiguration(
                            BackgroundJobSchedulingConfiguration.class,
                            GenerationTaskMaintenanceConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ThreadPoolTaskScheduler defaultScheduler = context.getBean(
                                "taskScheduler", ThreadPoolTaskScheduler.class);
                        ThreadPoolTaskScheduler maintenanceScheduler = context.getBean(
                                BackgroundJobSchedulingConfiguration
                                        .GENERATION_TASK_MAINTENANCE_SCHEDULER,
                                ThreadPoolTaskScheduler.class);
                        assertThat(defaultScheduler)
                                .as("普通后台任务不得复用生成任务 lease 专用 scheduler")
                                .isNotSameAs(maintenanceScheduler);
                        assertThat(defaultScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                                .isEqualTo(3);
                        assertThat(maintenanceScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                                .isEqualTo(4);
                        assertThat(await(recoveryStarted, Duration.ofSeconds(1)))
                                .as("恢复扫描必须先进入阻塞状态")
                                .isTrue();
                        assertThat(await(secondHeartbeat, Duration.ofMillis(750)))
                                .as("恢复扫描阻塞期间 lease 心跳仍必须独立执行")
                                .isTrue();
                    });
        } finally {
            releaseRecovery.countDown();
        }
    }

    private boolean await(CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
