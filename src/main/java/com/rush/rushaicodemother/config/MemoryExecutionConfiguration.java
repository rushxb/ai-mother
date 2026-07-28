package com.rush.rushaicodemother.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 记忆执行组件装配配置。
 */
@Configuration(proxyBeanMethods = false)
public class MemoryExecutionConfiguration {
    public static final String MEMORY_TASK_EXECUTOR = "memoryTaskExecutor";

    /** 创建并配置线程池任务执行器 Bean。 */
    @Bean(name = MEMORY_TASK_EXECUTOR)
    ThreadPoolTaskExecutor memoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("semantic-memory-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(256);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
