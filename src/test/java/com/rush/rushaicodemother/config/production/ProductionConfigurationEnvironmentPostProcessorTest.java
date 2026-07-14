package com.rush.rushaicodemother.config.production;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionConfigurationEnvironmentPostProcessorTest {

    private static final String SENSITIVE_VALUE = "must-not-appear-in-error";

    private final ProductionConfigurationEnvironmentPostProcessor processor =
            new ProductionConfigurationEnvironmentPostProcessor();

    @Test
    void shouldAcceptCompleteProductionConfiguration() {
        MockEnvironment environment = productionEnvironment(validProductionProperties());

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void shouldReportMissingAndBlankRequiredPropertiesWithoutExposingValues() {
        Map<String, Object> properties = validProductionProperties();
        properties.remove("spring.datasource.url");
        properties.put("spring.data.redis.host", "   ");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("spring.datasource.url"));
        assertTrue(exception.getMessage().contains("spring.data.redis.host"));
        assertFalse(exception.getMessage().contains(SENSITIVE_VALUE));
    }

    @Test
    void shouldAllowLocalhostAndEmptyNonSensitiveCredentials() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("spring.datasource.url", "jdbc:mysql://localhost:3306/app");
        properties.put("spring.datasource.username", "root");
        properties.put("spring.datasource.password", "");
        properties.put("spring.data.redis.host", "localhost");
        properties.put("spring.data.redis.password", "");
        properties.put("app.cors.allowed-origins", "http://localhost:5173");
        properties.put("code.deploy-host", "http://localhost:91");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(productionEnvironment(properties), null));
    }

    @Test
    void shouldTreatUnresolvedPlaceholderAsMissing() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("code.deploy-host", "${CODE_DEPLOY_HOST}");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("code.deploy-host"));
    }

    @Test
    void shouldNotRequireCosCredentialsWhenCosIsDisabled() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("cos.client.enabled", "false");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(productionEnvironment(properties), null));
    }

    @Test
    void shouldRequireAllCosPropertiesWhenCosIsEnabled() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("cos.client.enabled", "true");
        properties.put("cos.client.secret-id", SENSITIVE_VALUE);

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("cos.client.host"));
        assertTrue(exception.getMessage().contains("cos.client.secret-key"));
        assertTrue(exception.getMessage().contains("cos.client.region"));
        assertTrue(exception.getMessage().contains("cos.client.bucket"));
        assertFalse(exception.getMessage().contains(SENSITIVE_VALUE));
    }

    @Test
    void shouldRejectInvalidCosEnabledValue() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("cos.client.enabled", "enabled");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("cos.client.enabled"));
    }

    @Test
    void shouldRejectUnsafeProductionSwitches() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("server.servlet.session.cookie.secure", "false");
        properties.put("springdoc.api-docs.enabled", "true");
        properties.put("management.endpoint.health.probes.enabled", "false");
        properties.put("management.endpoint.health.show-details", "always");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("server.servlet.session.cookie.secure"));
        assertTrue(exception.getMessage().contains("springdoc.api-docs.enabled"));
        assertTrue(exception.getMessage().contains("management.endpoint.health.probes.enabled"));
        assertTrue(exception.getMessage().contains("management.endpoint.health.show-details"));
    }

    @Test
    void shouldIgnoreProductionContractOutsideProdProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void shouldBeRegisteredAsEnvironmentPostProcessor() throws IOException {
        Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/spring.factories");
        boolean registered = false;
        while (resources.hasMoreElements() && !registered) {
            URL resource = resources.nextElement();
            try (InputStream inputStream = resource.openStream()) {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                registered = content.contains(ProductionConfigurationEnvironmentPostProcessor.class.getName());
            }
        }

        assertTrue(registered);
    }

    private MockEnvironment productionEnvironment(Map<String, Object> properties) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addFirst(new MapPropertySource("test-production", properties));
        return environment;
    }

    private Map<String, Object> validProductionProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", "jdbc:mysql://db.internal:3306/app");
        properties.put("spring.datasource.username", "application");
        properties.put("spring.datasource.password", SENSITIVE_VALUE);
        properties.put("spring.data.redis.host", "redis.internal");
        properties.put("spring.data.redis.password", SENSITIVE_VALUE);
        properties.put("app.cors.allowed-origins", "https://console.example.com");
        properties.put("code.deploy-host", "https://deploy.example.com");
        properties.put("cos.client.enabled", "false");
        properties.put("server.servlet.session.cookie.secure", "true");
        properties.put("server.servlet.session.cookie.http-only", "true");
        properties.put("springdoc.api-docs.enabled", "false");
        properties.put("springdoc.swagger-ui.enabled", "false");
        properties.put("knife4j.enable", "false");
        properties.put("management.endpoint.health.probes.enabled", "true");
        properties.put("management.endpoint.health.show-details", "never");
        return properties;
    }
}
