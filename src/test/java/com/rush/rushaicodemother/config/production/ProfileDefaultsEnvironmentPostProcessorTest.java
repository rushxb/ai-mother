package com.rush.rushaicodemother.config.production;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@code prod} 与 {@code benchmark-worker} 的固定配置在删除 Profile yaml 后依然生效。
 */
class ProfileDefaultsEnvironmentPostProcessorTest {

    private final ProfileDefaultsEnvironmentPostProcessor processor =
            new ProfileDefaultsEnvironmentPostProcessor();

    @Test
    void shouldNotInjectAnythingWithoutProductionOrWorkerProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        processor.postProcessEnvironment(environment, null);

        assertFalse(environment.getPropertySources()
                .contains(ProfileDefaultsEnvironmentPostProcessor.PRODUCTION_PROPERTY_SOURCE));
        assertFalse(environment.getPropertySources()
                .contains(ProfileDefaultsEnvironmentPostProcessor.BENCHMARK_WORKER_PROPERTY_SOURCE));
    }

    @Test
    void shouldHardenOnlineProductionNode() {
        ConfigurableEnvironment environment = productionEnvironment();

        assertEquals("false", environment.getProperty("springdoc.api-docs.enabled"));
        assertEquals("false", environment.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("false", environment.getProperty("knife4j.enable"));
        assertEquals("true", environment.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("never", environment.getProperty("management.endpoint.health.show-details"));
        assertEquals("OFF", environment.getProperty(
                "logging.level.com.rush.rushaicodemother.mapper.AiModelMapper"));
        assertEquals("true", environment.getProperty("app.memory.long-term.tls-required"));
        assertEquals("true", environment.getProperty("app.memory.long-term.authentication-required"));
        assertEquals("true", environment.getProperty(
                "app.generated-code-sandbox.container.read-only-root"));
        assertEquals("container", environment.getProperty("app.generated-code-sandbox.mode"));
        assertEquals("redis", environment.getProperty("app.generation-task-queue.transport"));
        assertEquals("redis", environment.getProperty("app.generation-event-stream.transport"));
        assertEquals("true", environment.getProperty("app.background-jobs.enabled"));
    }

    @Test
    void shouldNotProvideDefaultsForCredentialsAndEndpoints() {
        ConfigurableEnvironment environment = productionEnvironment();

        // 未注入时必须解析为空值，绝不能内置可用的开发兜底凭据。
        for (String credentialProperty : new String[]{
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.data.redis.host",
                "spring.data.redis.password",
                "app.cors.allowed-origins",
                "code.deploy-host"
        }) {
            String value = environment.getProperty(credentialProperty);
            assertTrue(value == null || value.isBlank(),
                    "生产固定配置不得提供凭据或端点默认值: " + credentialProperty);
        }
    }

    @Test
    void workerProfileMustOverrideOnlineRoleSettings() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "benchmark-worker");

        processor.postProcessEnvironment(environment, null);

        assertEquals("none", environment.getProperty("spring.main.web-application-type"));
        assertEquals("false", environment.getProperty("app.background-jobs.enabled"));
        assertEquals("local", environment.getProperty("app.generation-task-queue.transport"));
        assertEquals("local", environment.getProperty("app.generation-event-stream.transport"));
        assertEquals("false", environment.getProperty("app.template-pre-warm.enabled"));
        assertEquals("true", environment.getProperty("app.generation-benchmark.worker.enabled"));
        // 生产硬化项在 Worker 角色下必须继续生效。
        assertEquals("never", environment.getProperty("management.endpoint.health.show-details"));
        assertEquals("true", environment.getProperty("app.memory.long-term.tls-required"));
    }

    @Test
    void deploymentEnvironmentVariablesMustWinOverProfileDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        // 模拟部署方通过 OS 环境变量显式覆盖固定配置。
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("app.generation-task-queue.transport", "local")));

        processor.postProcessEnvironment(environment, null);

        assertEquals("local", environment.getProperty("app.generation-task-queue.transport"));
    }

    @Test
    void profileDefaultsMustOverrideApplicationYamlBaseline() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        loadApplicationDefaults(environment);

        processor.postProcessEnvironment(environment, null);

        // application.yml 的基础默认值是 local，生产固定配置必须把它抬到 redis。
        assertEquals("redis", environment.getProperty("app.generation-task-queue.transport"));
        assertEquals("redis", environment.getProperty("app.generation-event-stream.transport"));
        assertEquals("container", environment.getProperty("app.generated-code-sandbox.mode"));
    }

    @Test
    void legacyDeploymentVariableNamesMustStillReachSpringProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        // 原先由 application-prod.yml 承载的部署变量名必须继续生效。
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of(
                                "MYSQL_URL", "jdbc:mysql://db.internal:3306/app",
                                "MYSQL_USERNAME", "application",
                                "MYSQL_PASSWORD", "production-database-password",
                                "REDIS_HOST", "redis.internal",
                                "REDIS_PASSWORD", "production-redis-password",
                                "CORS_ALLOWED_ORIGINS", "https://console.example.com",
                                "CODE_DEPLOY_HOST", "https://deploy.example.com"
                        )));

        processor.postProcessEnvironment(environment, null);

        assertEquals("jdbc:mysql://db.internal:3306/app",
                environment.getProperty("spring.datasource.url"));
        assertEquals("application", environment.getProperty("spring.datasource.username"));
        assertEquals("production-database-password",
                environment.getProperty("spring.datasource.password"));
        assertEquals("redis.internal", environment.getProperty("spring.data.redis.host"));
        assertEquals("production-redis-password",
                environment.getProperty("spring.data.redis.password"));
        assertEquals("https://console.example.com",
                environment.getProperty("app.cors.allowed-origins"));
        assertEquals("https://deploy.example.com", environment.getProperty("code.deploy-host"));
    }

    @Test
    void productionDefaultsMustSatisfyProductionValidator() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        // 模拟真实部署：凭据、主机和 Origin 通过 OS 环境变量注入，其余取值来自固定配置。
        Map<String, Object> injected = new LinkedHashMap<>(deploymentSecrets());
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, injected));
        loadApplicationDefaults(environment);

        processor.postProcessEnvironment(environment, null);

        assertDoesNotThrow(() -> new ProductionConfigurationEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null));
    }

    /** 返回注入固定配置后的生产环境。 */
    private ConfigurableEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        processor.postProcessEnvironment(environment, null);
        return environment;
    }

    /** 返回部署方必须提供的凭据与端点配置。 */
    private Map<String, Object> deploymentSecrets() {
        Map<String, Object> secrets = new LinkedHashMap<>();
        secrets.put("spring.datasource.url", "jdbc:mysql://db.internal:3306/app");
        secrets.put("spring.datasource.username", "application");
        secrets.put("spring.datasource.password", "production-database-password");
        secrets.put("spring.data.redis.host", "redis.internal");
        secrets.put("spring.data.redis.password", "production-redis-password");
        secrets.put("app.cors.allowed-origins", "https://console.example.com");
        secrets.put("code.deploy-host", "https://deploy.example.com");
        secrets.put("management.otlp.tracing.endpoint", "http://otel-collector:4318/v1/traces");
        secrets.put("app.dev-server.runtime.node-id", "preview-node-a");
        secrets.put("app.dev-server.internal-routing.shared-secret",
                "0123456789abcdef0123456789abcdef");
        secrets.put("app.memory.long-term.uri", "https://milvus.internal:19530");
        secrets.put("app.memory.long-term.token", "production-milvus-token");
        secrets.put("app.ai-model-secrets.active-key-id", "ai-model-key-2026-08");
        secrets.put("app.ai-model-secrets.active-key",
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        secrets.put("app.ai-model-secrets.fingerprint-key",
                "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");
        secrets.put("app.generation-benchmark.evidence.signing-secret",
                "production-benchmark-evidence-signing-secret");
        secrets.put("app.generated-code-sandbox.container.image",
                "registry.example.com/ai-code/sandbox@sha256:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        // 依赖源网络与 registry mirror 没有通用安全默认值，必须由部署方显式提供。
        secrets.put("app.generated-code-sandbox.container.dependency-network",
                "ai-code-sandbox-egress");
        secrets.put("app.generated-code-sandbox.container.dependency-registry-url",
                "http://npm-registry:4873/");
        return secrets;
    }

    /** 把 {@code application.yml} 的基础默认值加载为最低优先级属性源。 */
    private void loadApplicationDefaults(ConfigurableEnvironment environment) throws IOException {
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application-defaults", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
    }
}
