package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.TemplatePreWarmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 模板依赖预热的独立执行资源配置。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.template-pre-warm", name = "enabled", havingValue = "true")
public class TemplatePreWarmConfiguration {

    public static final String TEMPLATE_PRE_WARM_TASK_EXECUTOR = "templatePreWarmTaskExecutor";

    /**
 * 创建并配置线程池任务执行器 Bean。
 *
 * @param properties 配置属性
 * @return 配置完成的线程池任务执行器 Bean
 */
    @Bean(name = TEMPLATE_PRE_WARM_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor templatePreWarmTaskExecutor(TemplatePreWarmProperties properties) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(properties.getMaxConcurrency());
        taskExecutor.setMaxPoolSize(properties.getMaxConcurrency());
        taskExecutor.setQueueCapacity(properties.getTemplateIds().size());
        taskExecutor.setKeepAliveSeconds(30);
        taskExecutor.setAllowCoreThreadTimeOut(true);
        taskExecutor.setThreadNamePrefix("template-pre-warm-");
        taskExecutor.setWaitForTasksToCompleteOnShutdown(false);
        taskExecutor.setAwaitTerminationSeconds(10);
        return taskExecutor;
    }
}
