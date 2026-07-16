package com.rush.rushaicodemother.orchestration.runtime.task;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Registers one process-wide fixed-delay task for all generation-task lease maintenance. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
public class GenerationTaskMaintenanceConfiguration implements SchedulingConfigurer {

    private final GenerationTaskMaintenanceService maintenanceService;
    private final GenerationTaskLeaseProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(maintenanceService::runMaintenance, properties.getHeartbeatInterval());
    }
}
