package com.rush.rushaicodemother.orchestration.context;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiContextPackBudgetPropertiesTest {

    @Test
    void applicationYamlBindsProductionContextBudget() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        AiContextPackBudgetProperties properties = Binder.get(environment)
                .bind("app.ai-context-pack", Bindable.of(AiContextPackBudgetProperties.class))
                .orElseThrow(() -> new AssertionError("AI context pack configuration was not bound"));

        assertTrue(properties.isConfigurationValid());
        assertEquals(2_000, properties.maxTokens("vue_project"));
        assertEquals(1_500, properties.maxTokens("repair"));
        assertEquals("gpt-4o", properties.getTokenizerModel());
        assertEquals(1.15, properties.getTokenSafetyMargin());
    }

    @Test
    void directValidationRejectsUnsafeTokenizerMargins() {
        AiContextPackBudgetProperties properties = new AiContextPackBudgetProperties();

        properties.setTokenSafetyMargin(0.99);
        assertFalse(properties.isConfigurationValid());

        properties.setTokenSafetyMargin(2.01);
        assertFalse(properties.isConfigurationValid());

        properties.setTokenSafetyMargin(1.25);
        assertTrue(properties.isConfigurationValid());
    }
}
