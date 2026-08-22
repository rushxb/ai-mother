package com.rush.rushaicodemother.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 后台周期任务的线程资源与隔离策略。
 *
 * <p>普通扫描任务共享有界线程池；生成任务 lease 心跳与恢复使用独立线程池，
 * 避免结算、清理或外部资源维护阻塞活跃生成任务续租。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(BackgroundJobSchedulingProperties.class)
@ConditionalOnProperty(
        prefix = "app.background-jobs",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BackgroundJobSchedulingConfiguration {

    public static final String GENERATION_TASK_MAINTENANCE_SCHEDULER =
            "generationTaskMaintenanceScheduler";
    private static final String DEFAULT_TASK_SCHEDULER = "taskScheduler";

    /** 未显式指定 scheduler 的普通后台任务使用该有界线程池。 */
    @Bean(name = DEFAULT_TASK_SCHEDULER)
    ThreadPoolTaskScheduler backgroundJobTaskScheduler(BackgroundJobSchedulingProperties properties) {
        return scheduler(properties.getDefaultPoolSize(), "background-job-");
    }

    /** lease 心跳与恢复扫描专用，保证两类任务可以相互并发。 */
    @Bean(name = GENERATION_TASK_MAINTENANCE_SCHEDULER)
    ThreadPoolTaskScheduler generationTaskMaintenanceScheduler(BackgroundJobSchedulingProperties properties) {
        return scheduler(properties.getGenerationTaskMaintenancePoolSize(),
                "generation-task-maintenance-");
    }

    private ThreadPoolTaskScheduler scheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
