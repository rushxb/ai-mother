package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthProbeConfigurationTest {

    @Test
    void shouldSeparateProcessLivenessFromInfrastructureReadiness() throws IOException {
        PropertySourcesPropertyResolver resolver = loadApplicationProperties();

        assertEquals("livenessState,ping",
                resolver.getProperty("management.endpoint.health.group.liveness.include"));
        assertEquals("readinessState,db,redis",
                resolver.getProperty("management.endpoint.health.group.readiness.include"));
        assertEquals("true",
                resolver.getProperty("management.endpoint.health.probes.enabled"));
    }

    private PropertySourcesPropertyResolver loadApplicationProperties() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "application",
                new ClassPathResource("application.yml")
        );
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new PropertySourcesPropertyResolver(propertySources);
    }
}
