package com.rush.rushaicodemother.orchestration.learning;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationShadowRoutingPropertiesTest {

    @Test
    void applicationYamlShouldKeepShadowRoutingDisabledByDefault() throws Exception {
        StandardEnvironment environment = environment(Map.of());

        GenerationShadowRoutingProperties properties = bind(environment);

        assertFalse(properties.isEnabled());
    }

    @Test
    void environmentVariableShouldEnableShadowRoutingExplicitly() throws Exception {
        StandardEnvironment environment = environment(Map.of(
                "GENERATION_ROUTING_SHADOW_ENABLED", "true"
        ));

        GenerationShadowRoutingProperties properties = bind(environment);

        assertTrue(properties.isEnabled());
    }

    private StandardEnvironment environment(Map<String, Object> overrides) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "影子路由测试覆盖",
                    overrides
            ));
        }
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }

    private GenerationShadowRoutingProperties bind(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("app.generation-routing.shadow",
                        Bindable.of(GenerationShadowRoutingProperties.class))
                .orElseThrow(() -> new AssertionError("生成影子路由配置未绑定"));
    }
}