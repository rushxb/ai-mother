package com.rush.rushaicodemother.service.provisioning;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 为非关键的应用标题润色提供独立且有界的执行资源。 */
@Configuration(proxyBeanMethods = false)
class AppNameEnrichmentConfiguration {

    static final String APP_NAME_ENRICHMENT_EXECUTOR = "appNameEnrichmentExecutor";

    @Bean(name = APP_NAME_ENRICHMENT_EXECUTOR)
    ThreadPoolTaskExecutor appNameEnrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("app-name-enrichment-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
