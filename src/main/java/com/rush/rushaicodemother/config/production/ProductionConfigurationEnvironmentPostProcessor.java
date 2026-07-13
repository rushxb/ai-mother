package com.rush.rushaicodemother.config.production;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 在创建 Bean 前校验生产环境的必要配置和安全开关。
 *
 * <p>校验错误只报告配置键，不把配置值写入异常消息或日志，避免泄露密码、API Key 等敏感信息。</p>
 */
public class ProductionConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Profiles PRODUCTION_PROFILE = Profiles.of("prod");

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.data.redis.host",
            "app.cors.allowed-origins",
            "code.deploy-host"
    );

    private static final List<String> REQUIRED_COS_PROPERTIES = List.of(
            "cos.client.host",
            "cos.client.secret-id",
            "cos.client.secret-key",
            "cos.client.region",
            "cos.client.bucket"
    );

    private static final List<ExpectedPropertyValue> SECURITY_PROPERTIES = List.of(
            new ExpectedPropertyValue("server.servlet.session.cookie.secure", "true"),
            new ExpectedPropertyValue("server.servlet.session.cookie.http-only", "true"),
            new ExpectedPropertyValue("springdoc.api-docs.enabled", "false"),
            new ExpectedPropertyValue("springdoc.swagger-ui.enabled", "false"),
            new ExpectedPropertyValue("knife4j.enable", "false"),
            new ExpectedPropertyValue("management.endpoint.health.show-details", "never")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(PRODUCTION_PROFILE)) {
            return;
        }

        List<String> missingProperties = findMissingProperties(environment);
        List<String> unsafeProperties = findUnsafeProperties(environment);
        if (!missingProperties.isEmpty() || !unsafeProperties.isEmpty()) {
            throw new ProductionConfigurationException(buildFailureMessage(missingProperties, unsafeProperties));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private List<String> findMissingProperties(ConfigurableEnvironment environment) {
        List<String> missingProperties = new ArrayList<>();
        REQUIRED_PROPERTIES.stream()
                .filter(propertyName -> !hasTextProperty(environment, propertyName))
                .forEach(missingProperties::add);

        String cosEnabled = readProperty(environment, "cos.client.enabled");
        if (isInvalidBoolean(cosEnabled)) {
            return missingProperties;
        }
        if (Boolean.parseBoolean(normalize(cosEnabled))) {
            REQUIRED_COS_PROPERTIES.stream()
                    .filter(propertyName -> !hasTextProperty(environment, propertyName))
                    .forEach(missingProperties::add);
        }
        return missingProperties;
    }

    private List<String> findUnsafeProperties(ConfigurableEnvironment environment) {
        List<String> unsafeProperties = new ArrayList<>();
        SECURITY_PROPERTIES.stream()
                .filter(expected -> !expected.matches(readProperty(environment, expected.propertyName())))
                .map(ExpectedPropertyValue::description)
                .forEach(unsafeProperties::add);

        String cosEnabled = readProperty(environment, "cos.client.enabled");
        if (isInvalidBoolean(cosEnabled)) {
            unsafeProperties.add("cos.client.enabled（必须为 true 或 false）");
        }
        return unsafeProperties;
    }

    private boolean hasTextProperty(ConfigurableEnvironment environment, String propertyName) {
        String value = readProperty(environment, propertyName);
        return value != null && !value.isBlank() && !value.contains("${");
    }

    private String readProperty(ConfigurableEnvironment environment, String propertyName) {
        try {
            return environment.getProperty(propertyName);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isInvalidBoolean(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = normalize(value);
        return !"true".equals(normalized) && !"false".equals(normalized);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String buildFailureMessage(List<String> missingProperties, List<String> unsafeProperties) {
        List<String> violations = new ArrayList<>(2);
        if (!missingProperties.isEmpty()) {
            violations.add("缺失或空白配置项：" + String.join(", ", missingProperties));
        }
        if (!unsafeProperties.isEmpty()) {
            violations.add("不安全配置项：" + String.join(", ", unsafeProperties));
        }
        return "生产环境配置校验失败；" + String.join("；", violations);
    }

    private record ExpectedPropertyValue(String propertyName, String expectedValue) {

        private boolean matches(String actualValue) {
            return actualValue != null && expectedValue.equalsIgnoreCase(actualValue.trim());
        }

        private String description() {
            return propertyName + "（必须为 " + expectedValue + "）";
        }
    }
}
