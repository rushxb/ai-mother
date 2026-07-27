package com.rush.rushaicodemother.service.provisioning;

import com.rush.rushaicodemother.ai.AppNameGeneratorServiceFactory;
import com.rush.rushaicodemother.mapper.AppMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AppNameEnrichmentSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AppNameGeneratorServiceFactory.class,
                    () -> mock(AppNameGeneratorServiceFactory.class))
            .withBean(AppMapper.class, () -> mock(AppMapper.class))
            .withUserConfiguration(
                    AppNameEnrichmentConfiguration.class,
                    AppNameEnrichmentService.class
            );

    @Test
    void shouldWireDedicatedBoundedExecutor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AppNameEnrichmentService.class);
            ThreadPoolTaskExecutor executor = context.getBean(
                    AppNameEnrichmentConfiguration.APP_NAME_ENRICHMENT_EXECUTOR,
                    ThreadPoolTaskExecutor.class
            );
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isEqualTo(32);
        });
    }
}
