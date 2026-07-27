package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolLoopGuardPropertiesTest {

    @Test
    void applicationYamlMustBindProductionLoopLimits() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        AiToolLoopGuardProperties properties = Binder.get(environment)
                .bind("app.ai-tool-loop-guard", Bindable.of(AiToolLoopGuardProperties.class))
                .orElseThrow(() -> new AssertionError("AI 工具循环治理配置未绑定"));

        assertEquals(10_000, properties.getMaximumTrackedTasks());
        assertEquals(Duration.ofHours(2), properties.getRetention());
        assertEquals(2, properties.getMaxIdenticalCalls());
        assertEquals(6, properties.getMaxNoProgressCalls());
        assertEquals(24, properties.getHistorySize());
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void historyWindowMustCoverConfiguredDetectionThresholds() {
        AiToolLoopGuardProperties properties = new AiToolLoopGuardProperties();
        properties.setHistorySize(4);
        properties.setMaxNoProgressCalls(6);

        assertFalse(properties.isConfigurationValid());
    }
}
