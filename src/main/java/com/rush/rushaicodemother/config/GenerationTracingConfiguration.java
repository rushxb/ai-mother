package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps generation orchestration operational when distributed tracing is explicitly disabled. */
@Configuration(proxyBeanMethods = false)
public class GenerationTracingConfiguration {

    @Bean
    @ConditionalOnMissingBean(GenerationTraceContextBridge.class)
    @ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "false")
    GenerationTraceContextBridge noOpGenerationTraceContextBridge() {
        return GenerationTraceContextBridge.NOOP;
    }
}
