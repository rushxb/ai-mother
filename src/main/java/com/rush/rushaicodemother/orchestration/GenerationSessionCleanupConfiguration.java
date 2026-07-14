package com.rush.rushaicodemother.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 为生成会话注册表注册唯一的周期清理任务。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
public class GenerationSessionCleanupConfiguration implements SchedulingConfigurer {

    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationSessionProperties generationSessionProperties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(
                generationSessionRegistry::cleanupExpiredSessions,
                generationSessionProperties.getCleanupInterval()
        );
    }
}