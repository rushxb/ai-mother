package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSlaPropertiesTest {

    @Test
    void applicationYamlBindsEveryRouteProfile() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        GenerationSlaProperties properties = Binder.get(environment)
                .bind("app.generation-sla", Bindable.of(GenerationSlaProperties.class))
                .orElseThrow(() -> new AssertionError("generation SLA configuration was not bound"));

        assertTrue(properties.isConfigurationValid());
        assertEquals("create-preview-first", properties.profile(GenerationMode.CREATE).getName());
        assertEquals("agent-edit-saturated", properties.getSaturatedAgentEdit().getName());
    }
}
