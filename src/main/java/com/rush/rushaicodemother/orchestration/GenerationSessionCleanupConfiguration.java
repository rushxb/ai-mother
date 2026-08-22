package com.rush.rushaicodemother.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 为生成会话注册表注册唯一的周期清理任务。
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.background-jobs",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GenerationSessionCleanupConfiguration implements SchedulingConfigurer {

    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationSessionProperties generationSessionProperties;

    /**
 * 处理{@code configure}任务。
 *
 * @param taskRegistrar {@code taskRegistrar} 对应的调用参数
 */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(
                generationSessionRegistry::cleanupExpiredSessions,
                generationSessionProperties.getCleanupInterval()
        );
    }
}
