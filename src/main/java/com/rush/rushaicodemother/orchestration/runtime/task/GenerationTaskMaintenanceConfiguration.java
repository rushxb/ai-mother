package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.BackgroundJobSchedulingConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

/**
 * 为生成任务租约心跳与过期恢复提供相互隔离的周期调度。
 *
 * <p>恢复扫描可能执行数据库批处理，不能与活跃任务续租串在同一执行线程；
 * 否则一次慢恢复就可能让正常任务丢失 lease 并触发重复执行。</p>
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.background-jobs",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GenerationTaskMaintenanceConfiguration {

    private final GenerationTaskLeaseCoordinator leaseCoordinator;
    private final GenerationTaskRecoveryService recoveryService;

    /** 活跃任务续租使用独立周期，不能等待恢复扫描完成。 */
    @Scheduled(
            fixedDelayString = "#{@generationTaskLeaseProperties.heartbeatInterval.toMillis()}",
            timeUnit = TimeUnit.MILLISECONDS,
            scheduler = BackgroundJobSchedulingConfiguration.GENERATION_TASK_MAINTENANCE_SCHEDULER)
    public void heartbeatTrackedTasks() {
        leaseCoordinator.heartbeatTrackedTasks();
    }

    /** 过期任务恢复使用自己的周期，并与 lease 心跳并发隔离。 */
    @Scheduled(
            fixedDelayString = "#{@generationTaskLeaseProperties.recoveryScanInterval.toMillis()}",
            timeUnit = TimeUnit.MILLISECONDS,
            scheduler = BackgroundJobSchedulingConfiguration.GENERATION_TASK_MAINTENANCE_SCHEDULER)
    public void recoverExpiredTasks() {
        recoveryService.recoverExpiredTasks();
    }
}
