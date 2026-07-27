package com.rush.rushaicodemother.config.production;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    void shouldRequireDatabaseAndRedisCredentials() {
        Map<String, Object> properties = validProductionProperties();
        properties.remove("spring.datasource.password");
        properties.put("spring.data.redis.password", "   ");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("spring.datasource.password"));
        assertTrue(exception.getMessage().contains("spring.data.redis.password"));
        assertFalse(exception.getMessage().contains(SENSITIVE_VALUE));
    }

    @Test
    void shouldRejectPrivilegedDatabaseAccountAndWeakCredentialsWithoutExposingValues() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("spring.datasource.username", "root");
        properties.put("spring.datasource.password", "123456");
        properties.put("spring.data.redis.password", "changeme");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("spring.datasource.username"));
        assertTrue(exception.getMessage().contains("spring.datasource.password"));
        assertTrue(exception.getMessage().contains("spring.data.redis.password"));
        assertFalse(exception.getMessage().contains("123456"));
        assertFalse(exception.getMessage().contains("changeme"));
    }

    @Test
    void shouldRejectLoopbackOrWildcardPublicOrigins() {
        for (String origins : new String[]{
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://[::1]:5173",
                "https://*.example.com"
        }) {
            Map<String, Object> properties = validProductionProperties();
            properties.put("app.cors.allowed-origins", origins);

            ProductionConfigurationException exception = assertThrows(
                    ProductionConfigurationException.class,
                    () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
            );

            assertTrue(exception.getMessage().contains("app.cors.allowed-origins"));
            assertFalse(exception.getMessage().contains(origins));
        }
    }

    @Test
    void shouldRejectLoopbackDeploymentHost() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("code.deploy-host", "http://localhost:91");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("code.deploy-host"));
        assertFalse(exception.getMessage().contains("http://localhost:91"));
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
        properties.put("logging.level.com.rush.rushaicodemother.mapper.AiModelMapper", "DEBUG");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("server.servlet.session.cookie.secure"));
        assertTrue(exception.getMessage().contains("springdoc.api-docs.enabled"));
        assertTrue(exception.getMessage().contains("management.endpoint.health.probes.enabled"));
        assertTrue(exception.getMessage().contains("management.endpoint.health.show-details"));
        assertTrue(exception.getMessage().contains("AiModelMapper"));
    }

    @Test
    void shouldRejectDisabledPromptCatalogInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.ai-prompt-catalog.enabled", "false");
        properties.put("app.ai-prompt-catalog.runtime-releases.enabled", "false");
        properties.put("app.ai-prompt-catalog.runtime-releases.initial-load-required", "false");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.ai-prompt-catalog.enabled"));
        assertTrue(exception.getMessage().contains("app.ai-prompt-catalog.runtime-releases.enabled"));
        assertTrue(exception.getMessage().contains(
                "app.ai-prompt-catalog.runtime-releases.initial-load-required"));
    }

    @Test
    void shouldRejectDisabledOrFailOpenModelCapacityPolicyInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.ai-model-capacity.enabled", "false");
        properties.put("app.ai-model-capacity.fail-open", "true");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.ai-model-capacity.enabled"));
        assertTrue(exception.getMessage().contains("app.ai-model-capacity.fail-open"));
    }

    @Test
    void shouldRequireStrongBenchmarkEvidenceSigningSecretWithoutExposingIt() {
        Map<String, Object> missing = validProductionProperties();
        missing.remove("app.generation-benchmark.evidence.signing-secret");

        ProductionConfigurationException missingException = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(missing), null)
        );
        assertTrue(missingException.getMessage().contains(
                "app.generation-benchmark.evidence.signing-secret"));

        Map<String, Object> weak = validProductionProperties();
        String weakSecret = "benchmark-secret-too-short";
        weak.put("app.generation-benchmark.evidence.signing-secret", weakSecret);

        ProductionConfigurationException weakException = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(weak), null)
        );
        assertTrue(weakException.getMessage().contains(
                "app.generation-benchmark.evidence.signing-secret"));
        assertFalse(weakException.getMessage().contains(weakSecret));
    }

    @Test
    void shouldRequireAuthenticatedTlsMilvusWithStartupVerification() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.memory.long-term.uri", "http://milvus.internal:19530");
        properties.put("app.memory.long-term.authentication-required", "false");
        properties.put("app.memory.long-term.tls-required", "false");
        properties.put("app.memory.long-term.verify-on-startup", "false");
        properties.put("app.memory.outbox.enabled", "false");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.memory.long-term.uri"));
        assertTrue(exception.getMessage().contains("app.memory.long-term.authentication-required"));
        assertTrue(exception.getMessage().contains("app.memory.long-term.tls-required"));
        assertTrue(exception.getMessage().contains("app.memory.long-term.verify-on-startup"));
        assertTrue(exception.getMessage().contains("app.memory.outbox.enabled"));
    }

    @Test
    void shouldRejectLoopbackMilvusEndpointInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.memory.long-term.uri", "https://127.0.0.1:19530");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.memory.long-term.uri"));
    }

    @Test
    void shouldRequireAiModelEnvelopeKeysInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.remove("app.ai-model-secrets.active-key");
        properties.remove("app.ai-model-secrets.fingerprint-key");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.ai-model-secrets.active-key"));
        assertTrue(exception.getMessage().contains("app.ai-model-secrets.fingerprint-key"));
        assertFalse(exception.getMessage().contains(SENSITIVE_VALUE));
    }

    @Test
    void shouldRejectLocalGenerationTransportsInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.generation-event-stream.transport", "local");
        properties.put("app.generation-task-queue.transport", "local");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.generation-event-stream.transport"));
        assertTrue(exception.getMessage().contains("app.generation-task-queue.transport"));
    }

    @Test
    void benchmarkWorkerMustRequireIsolatedLocalTransportsAndGraders() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.generation-benchmark.worker.enabled", "true");
        properties.put("app.generation-event-stream.transport", "local");
        properties.put("app.generation-task-queue.transport", "local");
        properties.put("app.background-jobs.enabled", "false");
        properties.put("app.generation-benchmark.browser-grading.enabled", "true");
        properties.put("app.generation-benchmark.backend-grading.enabled", "true");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(
                productionEnvironment(properties), null));

        properties.put("app.background-jobs.enabled", "true");
        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );
        assertTrue(exception.getMessage().contains("app.background-jobs.enabled"));
    }

    @Test
    void shouldRejectInvalidOrReusedAiModelEnvelopeKeys() {
        Map<String, Object> invalid = validProductionProperties();
        invalid.put("app.ai-model-secrets.active-key-id", "../../unsafe");
        invalid.put("app.ai-model-secrets.active-key", "not-base64");

        ProductionConfigurationException invalidException = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(invalid), null)
        );
        assertTrue(invalidException.getMessage().contains("app.ai-model-secrets.active-key-id"));
        assertTrue(invalidException.getMessage().contains("app.ai-model-secrets.active-key"));

        Map<String, Object> reused = validProductionProperties();
        reused.put("app.ai-model-secrets.fingerprint-key",
                reused.get("app.ai-model-secrets.active-key"));

        ProductionConfigurationException reusedException = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(reused), null)
        );
        assertTrue(reusedException.getMessage().contains("key purposes must be separated"));
    }

    @Test
    void shouldRejectHostLocalGeneratedCodeSandboxInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.generated-code-sandbox.mode", "host-local");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.generated-code-sandbox.mode"));
        assertFalse(exception.getMessage().contains(SENSITIVE_VALUE));
    }

    @Test
    void shouldRejectAdvertisedButUnimplementedSandboxBackendsInProduction() {
        for (String mode : new String[]{"isolated-worker", "microvm", "remote-executor"}) {
            Map<String, Object> properties = validProductionProperties();
            properties.put("app.generated-code-sandbox.mode", mode);

            ProductionConfigurationException exception = assertThrows(
                    ProductionConfigurationException.class,
                    () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
            );

            assertTrue(exception.getMessage().contains("app.generated-code-sandbox.mode"));
        }
    }

    @Test
    void shouldRequireContainerReadinessVerificationInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.generated-code-sandbox.container.verify-on-startup", "false");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains(
                "app.generated-code-sandbox.container.verify-on-startup"));
    }

    @Test
    void shouldRequireDependencyCacheInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.generated-code-sandbox.container.dependency-cache-enabled", "false");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains(
                "app.generated-code-sandbox.container.dependency-cache-enabled"));
    }

    @Test
    void shouldRejectUnsafePnpmStoreConfigurationInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.generated-code-sandbox.container.pnpm-store-volume", "../host-cache");
        properties.put("app.generated-code-sandbox.container.pnpm-store-mount", "/workspace/store");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains(
                "app.generated-code-sandbox.container.pnpm-store-volume"));
        assertTrue(exception.getMessage().contains(
                "app.generated-code-sandbox.container.pnpm-store-mount"));
    }

    @Test
    void shouldRequireStableDevServerNodeIdentityInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.remove("app.dev-server.runtime.node-id");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.dev-server.runtime.node-id"));
    }

    @Test
    void shouldRejectUnroutableDevServerNodeIdentityInProduction() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.dev-server.runtime.node-id", "preview-node/../../admin");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.dev-server.runtime.node-id"));
    }

    @Test
    void shouldRequireProductionTraceExport() {
        Map<String, Object> properties = validProductionProperties();
        properties.remove("management.otlp.tracing.endpoint");
        properties.put("management.otlp.tracing.export.enabled", "false");

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("management.otlp.tracing.endpoint"));
        assertTrue(exception.getMessage().contains("management.otlp.tracing.export.enabled"));
    }

    @Test
    void shouldRequireDigestPinnedSandboxImageAndDedicatedNetworks() {
        Map<String, Object> properties = validProductionProperties();
        properties.put("app.generated-code-sandbox.container.image", "sandbox:latest");
        properties.put("app.generated-code-sandbox.container.dependency-network", "bridge");
        properties.put(
                "app.generated-code-sandbox.container.dev-server-network",
                "bridge"
        );

        ProductionConfigurationException exception = assertThrows(
                ProductionConfigurationException.class,
                () -> processor.postProcessEnvironment(productionEnvironment(properties), null)
        );

        assertTrue(exception.getMessage().contains("app.generated-code-sandbox.container.image"));
        assertTrue(exception.getMessage().contains("app.generated-code-sandbox.container.dependency-network"));
        assertTrue(exception.getMessage().contains("app.generated-code-sandbox.container.dev-server-network"));
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

    @Test
    void productionProfileMustNotPackageDevelopmentCredentialOrEndpointDefaults() throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("application-prod.yml")) {
            assertTrue(inputStream != null, "缺少 application-prod.yml");
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(content.contains("url: ${MYSQL_URL}"));
            assertTrue(content.contains("username: ${MYSQL_USERNAME}"));
            assertTrue(content.contains("password: ${MYSQL_PASSWORD}"));
            assertTrue(content.contains("host: ${REDIS_HOST}"));
            assertTrue(content.contains("password: ${REDIS_PASSWORD}"));
            assertTrue(content.contains("allowed-origins: ${CORS_ALLOWED_ORIGINS}"));
            assertTrue(content.contains("deploy-host: ${CODE_DEPLOY_HOST}"));
            assertTrue(content.contains("active-key-id: ${AI_MODEL_SECRET_ACTIVE_KEY_ID}"));
            assertTrue(content.contains("active-key: ${AI_MODEL_SECRET_ACTIVE_KEY}"));
            assertTrue(content.contains("fingerprint-key: ${AI_MODEL_SECRET_FINGERPRINT_KEY}"));
            assertTrue(content.contains(
                    "transport: ${GENERATION_EVENT_STREAM_TRANSPORT:redis}"));
            assertTrue(content.contains(
                    "transport: ${GENERATION_TASK_QUEUE_TRANSPORT:redis}"));
            assertTrue(content.contains(
                    "signing-secret: ${GENERATION_BENCHMARK_EVIDENCE_SIGNING_SECRET}"));
            assertTrue(content.contains("uri: ${MILVUS_URI}"));
            assertTrue(content.contains("token: ${MILVUS_TOKEN}"));
            assertTrue(content.contains("authentication-required: ${MILVUS_AUTHENTICATION_REQUIRED:true}"));
            assertTrue(content.contains("tls-required: ${MILVUS_TLS_REQUIRED:true}"));
            assertTrue(content.contains("verify-on-startup: ${MILVUS_VERIFY_ON_STARTUP:true}"));
            assertTrue(content.contains(
                    "dependency-cache-enabled: ${GENERATED_CODE_SANDBOX_DEPENDENCY_CACHE_ENABLED:true}"));
            assertFalse(content.contains("MYSQL_PASSWORD:123456"));
            assertFalse(content.contains("CORS_ALLOWED_ORIGINS:http://localhost"));
            assertFalse(content.contains("CODE_DEPLOY_HOST:http://localhost"));
        }
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
        properties.put("app.generated-code-sandbox.mode", "container");
        properties.put(
                "app.generated-code-sandbox.container.image",
                "registry.example.com/ai-code/sandbox@sha256:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
        properties.put("app.generated-code-sandbox.container.workspace-mount", "/workspace");
        properties.put(
                "app.generated-code-sandbox.container.dependency-network",
                "ai-code-sandbox-egress"
        );
        properties.put(
                "app.generated-code-sandbox.container.dev-server-network",
                "ai-code-sandbox-internal"
        );
        properties.put(
                "app.generated-code-sandbox.container.preview-gateway-network",
                "ai-code-sandbox-preview-gateway"
        );
        properties.put("app.generated-code-sandbox.container.dependency-cache-enabled", "true");
        properties.put(
                "app.generated-code-sandbox.container.pnpm-store-volume",
                "ai-code-mother-pnpm-store-v9"
        );
        properties.put(
                "app.generated-code-sandbox.container.pnpm-store-mount",
                "/pnpm/store"
        );
        properties.put("app.dev-server.runtime.node-id", "preview-node-a");
        properties.put(
                "app.dev-server.internal-routing.base-url-template",
                "http://{nodeId}:8123/api"
        );
        properties.put(
                "app.dev-server.internal-routing.shared-secret",
                "0123456789abcdef0123456789abcdef"
        );
        properties.put("app.generated-code-sandbox.container.read-only-root", "true");
        properties.put("app.generated-code-sandbox.container.verify-on-startup", "true");
        properties.put("code.deploy-host", "https://deploy.example.com");
        properties.put("cos.client.enabled", "false");
        properties.put("server.servlet.session.cookie.secure", "true");
        properties.put("server.servlet.session.cookie.http-only", "true");
        properties.put("springdoc.api-docs.enabled", "false");
        properties.put("springdoc.swagger-ui.enabled", "false");
        properties.put("knife4j.enable", "false");
        properties.put("management.endpoint.health.probes.enabled", "true");
        properties.put("management.endpoint.health.show-details", "never");
        properties.put("management.tracing.enabled", "true");
        properties.put("management.otlp.tracing.export.enabled", "true");
        properties.put("management.otlp.tracing.endpoint", "http://otel-collector:4318/v1/traces");
        properties.put("logging.level.com.rush.rushaicodemother.mapper.AiModelMapper", "OFF");
        properties.put("app.generation-event-stream.transport", "redis");
        properties.put("app.generation-task-queue.transport", "redis");
        properties.put("app.background-jobs.enabled", "true");
        properties.put(
                "app.generation-benchmark.evidence.signing-secret",
                "0123456789abcdef0123456789abcdef"
        );
        properties.put("app.ai-model-capacity.enabled", "true");
        properties.put("app.ai-model-capacity.fail-open", "false");
        properties.put("app.ai-model-secrets.active-key-id", "production-kek-v1");
        properties.put("app.ai-model-secrets.active-key", encodedKey(1));
        properties.put("app.ai-model-secrets.fingerprint-key", encodedKey(65));
        properties.put("app.ai-prompt-catalog.enabled", "true");
        properties.put("app.ai-prompt-catalog.runtime-releases.enabled", "true");
        properties.put("app.ai-prompt-catalog.runtime-releases.initial-load-required", "true");
        properties.put("app.memory.long-term.enabled", "true");
        properties.put("app.memory.long-term.uri", "https://milvus.internal:19530");
        properties.put("app.memory.long-term.token", SENSITIVE_VALUE);
        properties.put("app.memory.long-term.authentication-required", "true");
        properties.put("app.memory.long-term.tls-required", "true");
        properties.put("app.memory.long-term.verify-on-startup", "true");
        properties.put("app.memory.outbox.enabled", "true");
        return properties;
    }

    private String encodedKey(int start) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (start + index);
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
