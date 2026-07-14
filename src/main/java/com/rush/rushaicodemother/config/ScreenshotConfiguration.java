package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.infrastructure.screenshot.selenium.SeleniumChromeDriverFactory;
import com.rush.rushaicodemother.infrastructure.screenshot.selenium.SeleniumWebPageScreenshotRenderer;
import com.rush.rushaicodemother.service.screenshot.DefaultScreenshotService;
import com.rush.rushaicodemother.service.screenshot.DisabledScreenshotService;
import com.rush.rushaicodemother.service.screenshot.ScreenshotService;
import com.rush.rushaicodemother.service.screenshot.WebPageScreenshotRenderer;
import com.rush.rushaicodemother.service.storage.ObjectStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 网页截图运行时装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScreenshotProperties.class)
public class ScreenshotConfiguration {

    public static final String SCREENSHOT_TASK_EXECUTOR = "screenshotTaskExecutor";

    @Bean
    public SeleniumChromeDriverFactory seleniumChromeDriverFactory(ScreenshotProperties properties) {
        return new SeleniumChromeDriverFactory(properties);
    }

    @Bean(name = SCREENSHOT_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor screenshotTaskExecutor(ScreenshotProperties properties) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(properties.getMaxConcurrency());
        taskExecutor.setMaxPoolSize(properties.getMaxConcurrency());
        taskExecutor.setQueueCapacity(properties.getQueueCapacity());
        taskExecutor.setKeepAliveSeconds(30);
        taskExecutor.setAllowCoreThreadTimeOut(true);
        taskExecutor.setThreadNamePrefix("deployment-screenshot-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(false);
        taskExecutor.setAwaitTerminationSeconds(10);
        return taskExecutor;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.screenshot", name = "enabled", havingValue = "true")
    public WebPageScreenshotRenderer webPageScreenshotRenderer(
            SeleniumChromeDriverFactory driverFactory,
            ScreenshotProperties properties) {
        return new SeleniumWebPageScreenshotRenderer(driverFactory, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.screenshot", name = "enabled", havingValue = "true")
    public ScreenshotService screenshotService(WebPageScreenshotRenderer screenshotRenderer,
                                               ObjectStorageService objectStorageService,
                                               ScreenshotProperties screenshotProperties,
                                               CodeDeploymentProperties deploymentProperties) {
        return new DefaultScreenshotService(
                screenshotRenderer,
                objectStorageService,
                screenshotProperties,
                deploymentProperties
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.screenshot",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public ScreenshotService disabledScreenshotService() {
        return new DisabledScreenshotService();
    }
}
