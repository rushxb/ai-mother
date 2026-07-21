package com.rush.rushaicodemother.infrastructure.telemetry;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.NoopTracerAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerTracingAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    NoopTracerAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class
            ))
            .withPropertyValues(
                    "management.tracing.enabled=true",
                    "management.tracing.propagation.consume=W3C",
                    "management.tracing.propagation.produce=W3C"
            );

    @Test
    void enabledApplicationAssemblyMustExposeOneRealTracerAndPropagator() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Tracer.class);
            assertThat(context).hasSingleBean(Propagator.class);
            assertThat(context.getBean(Tracer.class)).isNotSameAs(Tracer.NOOP);
            assertThat(context.getBean(Propagator.class)).isNotSameAs(Propagator.NOOP);
        });
    }
}
