package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.config.AiModelCircuitBreakerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelCircuitBreakerSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiModelCircuitBreakerProperties.class, AiModelCircuitBreaker.class);

    @Test
    void shouldCreateCircuitBreakerUsingProductionConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiModelCircuitBreaker.class);
        });
    }
}
