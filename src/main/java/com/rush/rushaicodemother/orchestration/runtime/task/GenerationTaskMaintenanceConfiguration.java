package com.rush.rushaicodemother.orchestration.runtime.task;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** 为所有生成任务租约维护注册一个进程范围的固定延迟任务。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.background-jobs",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GenerationTaskMaintenanceConfiguration implements SchedulingConfigurer {

    private final GenerationTaskMaintenanceService maintenanceService;
    private final GenerationTaskLeaseProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(maintenanceService::runMaintenance, properties.getHeartbeatInterval());
    }
}
