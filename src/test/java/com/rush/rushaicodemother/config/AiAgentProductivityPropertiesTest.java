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

class AiAgentProductivityPropertiesTest {

    @Test
    void applicationYamlMustBindProductionProductivityLimits() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        AiAgentProductivityProperties properties = Binder.get(environment)
                .bind("app.ai-agent-productivity",
                        Bindable.of(AiAgentProductivityProperties.class))
                .orElseThrow(() -> new AssertionError("AI Agent 生产率治理配置未绑定"));

        assertEquals(10_000, properties.getMaximumTrackedTasks());
        assertEquals(Duration.ofHours(2), properties.getRetention());
        assertEquals(8, properties.getMaxReadOnlyCallsWithoutMutation());
        assertEquals(3, properties.getMaxModelTurnsWithoutMutation());
        assertEquals(2, properties.getForcedActionTurnsBeforeFinalize());
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void retentionMustBePositive() {
        AiAgentProductivityProperties properties = new AiAgentProductivityProperties();
        properties.setRetention(Duration.ZERO);

        assertFalse(properties.isConfigurationValid());
    }
}
