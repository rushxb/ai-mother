package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTracingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GenerationTracingConfiguration.class);

    @Test
    void disabledTracingMustInstallTheExplicitNoOpBridge() {
        contextRunner
                .withPropertyValues("management.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(GenerationTraceContextBridge.class);
                    assertThat(context.getBean(GenerationTraceContextBridge.class))
                            .isSameAs(GenerationTraceContextBridge.NOOP);
                });
    }

    @Test
    void enabledTracingMustLeaveTheBridgeToTheMicrometerAdapter() {
        contextRunner
                .withPropertyValues("management.tracing.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(GenerationTraceContextBridge.class));
    }
}
