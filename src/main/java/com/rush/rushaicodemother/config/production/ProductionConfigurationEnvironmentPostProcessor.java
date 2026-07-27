package com.rush.rushaicodemother.config.production;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
            "spring.datasource.password",
            "spring.data.redis.host",
            "spring.data.redis.password",
            "app.cors.allowed-origins",
            "app.generated-code-sandbox.mode",
            "app.generated-code-sandbox.container.image",
            "app.generated-code-sandbox.container.workspace-mount",
            "app.generated-code-sandbox.container.dependency-network",
            "app.generated-code-sandbox.container.dev-server-network",
            "app.generated-code-sandbox.container.preview-gateway-network",
            "app.generated-code-sandbox.container.pnpm-store-volume",
            "app.generated-code-sandbox.container.pnpm-store-mount",
            "app.dev-server.runtime.node-id",
            "app.dev-server.internal-routing.base-url-template",
            "app.dev-server.internal-routing.shared-secret",
            "app.ai-model-secrets.active-key-id",
            "app.ai-model-secrets.active-key",
            "app.ai-model-secrets.fingerprint-key",
            "app.generation-benchmark.evidence.signing-secret",
            "app.memory.long-term.uri",
            "app.memory.long-term.token",
            "management.otlp.tracing.endpoint",
            "code.deploy-host"
    );

    private static final Set<String> PRODUCTION_SANDBOX_MODES = Set.of(
            "container"
    );

    private static final Set<String> PROHIBITED_PRODUCTION_SECRETS = Set.of(
            "123456",
            "password",
            "root",
            "admin",
            "changeme",
            "changeit",
            "secret"
    );

    private static final int MINIMUM_PRODUCTION_SECRET_LENGTH = 16;

    private static final List<String> REQUIRED_COS_PROPERTIES = List.of(
            "cos.client.host",
            "cos.client.secret-id",
            "cos.client.secret-key",
            "cos.client.region",
            "cos.client.bucket"
    );

    private static final List<ExpectedPropertyValue> REQUIRED_PROPERTY_VALUES = List.of(
            new ExpectedPropertyValue("server.servlet.session.cookie.secure", "true"),
            new ExpectedPropertyValue("server.servlet.session.cookie.http-only", "true"),
            new ExpectedPropertyValue("springdoc.api-docs.enabled", "false"),
            new ExpectedPropertyValue("springdoc.swagger-ui.enabled", "false"),
            new ExpectedPropertyValue("knife4j.enable", "false"),
            new ExpectedPropertyValue("management.endpoint.health.probes.enabled", "true"),
            new ExpectedPropertyValue("management.endpoint.health.show-details", "never"),
            new ExpectedPropertyValue("management.tracing.enabled", "true"),
            new ExpectedPropertyValue("management.otlp.tracing.export.enabled", "true"),
            new ExpectedPropertyValue(
                    "logging.level.com.rush.rushaicodemother.mapper.AiModelMapper", "OFF"),
            new ExpectedPropertyValue("app.ai-model-capacity.enabled", "true"),
            new ExpectedPropertyValue("app.ai-model-capacity.fail-open", "false"),
            new ExpectedPropertyValue("app.ai-prompt-catalog.enabled", "true"),
            new ExpectedPropertyValue("app.ai-prompt-catalog.runtime-releases.enabled", "true"),
            new ExpectedPropertyValue(
                    "app.ai-prompt-catalog.runtime-releases.initial-load-required", "true"),
            new ExpectedPropertyValue("app.memory.long-term.enabled", "true"),
            new ExpectedPropertyValue("app.memory.long-term.authentication-required", "true"),
            new ExpectedPropertyValue("app.memory.long-term.tls-required", "true"),
            new ExpectedPropertyValue("app.memory.long-term.verify-on-startup", "true"),
            new ExpectedPropertyValue("app.memory.outbox.enabled", "true"),
            new ExpectedPropertyValue("app.generated-code-sandbox.container.read-only-root", "true"),
            new ExpectedPropertyValue("app.generated-code-sandbox.container.verify-on-startup", "true"),
            new ExpectedPropertyValue(
                    "app.generated-code-sandbox.container.dependency-cache-enabled", "true")
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
        REQUIRED_PROPERTY_VALUES.stream()
                .filter(expected -> !expected.matches(readProperty(environment, expected.propertyName())))
                .map(ExpectedPropertyValue::description)
                .forEach(unsafeProperties::add);

        String cosEnabled = readProperty(environment, "cos.client.enabled");
        if (isInvalidBoolean(cosEnabled)) {
            unsafeProperties.add("cos.client.enabled（必须为 true 或 false）");
        }
        String sandboxMode = readProperty(environment, "app.generated-code-sandbox.mode");
        if (hasTextValue(sandboxMode) && !PRODUCTION_SANDBOX_MODES.contains(normalize(sandboxMode))) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.mode（当前生产版本必须使用已实现的 container 后端）"
            );
        }
        validateContainerSandboxSupplyChainAndNetworks(environment, unsafeProperties);
        validateDevServerNodeIdentity(environment, unsafeProperties);
        validateDevServerInternalRouting(environment, unsafeProperties);
        validateProcessRole(environment, unsafeProperties);
        validateCredentialPolicy(environment, unsafeProperties);
        validateSecret(
                environment,
                unsafeProperties,
                "app.generation-benchmark.evidence.signing-secret",
                32
        );
        validateMilvusMemory(environment, unsafeProperties);
        validateAiModelSecretKeys(environment, unsafeProperties);
        validatePublicEndpointPolicy(environment, unsafeProperties);
        return unsafeProperties;
    }

    private void validateProcessRole(ConfigurableEnvironment environment,
                                     List<String> unsafeProperties) {
        String workerEnabled = readProperty(
                environment, "app.generation-benchmark.worker.enabled");
        if (isInvalidBoolean(workerEnabled)) {
            unsafeProperties.add(
                    "app.generation-benchmark.worker.enabled（必须为 true 或 false）");
            return;
        }
        boolean worker = Boolean.parseBoolean(normalize(workerEnabled));
        if (worker) {
            requireValue(environment, unsafeProperties,
                    "app.generation-event-stream.transport", "local");
            requireValue(environment, unsafeProperties,
                    "app.generation-task-queue.transport", "local");
            requireValue(environment, unsafeProperties,
                    "app.background-jobs.enabled", "false");
            requireValue(environment, unsafeProperties,
                    "app.generation-benchmark.browser-grading.enabled", "true");
            requireValue(environment, unsafeProperties,
                    "app.generation-benchmark.backend-grading.enabled", "true");
            return;
        }
        requireValue(environment, unsafeProperties,
                "app.generation-event-stream.transport", "redis");
        requireValue(environment, unsafeProperties,
                "app.generation-task-queue.transport", "redis");
        requireValue(environment, unsafeProperties,
                "app.background-jobs.enabled", "true");
    }

    private void requireValue(ConfigurableEnvironment environment,
                              List<String> unsafeProperties,
                              String propertyName,
                              String expectedValue) {
        ExpectedPropertyValue expected = new ExpectedPropertyValue(
                propertyName, expectedValue);
        if (!expected.matches(readProperty(environment, propertyName))) {
            unsafeProperties.add(expected.description());
        }
    }

    private void validateCredentialPolicy(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties
    ) {
        String datasourceUsername = readProperty(environment, "spring.datasource.username");
        if (hasTextValue(datasourceUsername) && "root".equals(normalize(datasourceUsername))) {
            unsafeProperties.add(
                    "spring.datasource.username（生产环境禁止使用 root 超级账号）"
            );
        }
        validateSecret(environment, unsafeProperties, "spring.datasource.password");
        validateSecret(environment, unsafeProperties, "spring.data.redis.password");
    }

    private void validateMilvusMemory(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties
    ) {
        validateSecret(environment, unsafeProperties, "app.memory.long-term.token");
        String endpoint = readProperty(environment, "app.memory.long-term.uri");
        if (!hasTextValue(endpoint)) {
            return;
        }
        try {
            URI uri = new URI(endpoint.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !hasTextValue(uri.getHost())
                    || isLoopbackHost(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
                unsafeProperties.add(
                        "app.memory.long-term.uri (production Milvus must use a non-loopback, credential-free HTTPS endpoint)");
            }
        } catch (URISyntaxException invalidEndpoint) {
            unsafeProperties.add("app.memory.long-term.uri (must be a valid HTTPS endpoint)");
        }
    }

    private void validateAiModelSecretKeys(ConfigurableEnvironment environment,
                                           List<String> unsafeProperties) {
        String keyId = readProperty(environment, "app.ai-model-secrets.active-key-id");
        if (hasTextValue(keyId) && !keyId.trim().matches("[A-Za-z0-9._-]{1,64}")) {
            unsafeProperties.add(
                    "app.ai-model-secrets.active-key-id (must be a stable, routable key identifier)");
        }

        byte[] activeKey = decodeBase64Key(
                readProperty(environment, "app.ai-model-secrets.active-key"));
        byte[] fingerprintKey = decodeBase64Key(
                readProperty(environment, "app.ai-model-secrets.fingerprint-key"));
        try {
            if (hasTextProperty(environment, "app.ai-model-secrets.active-key") && activeKey == null) {
                unsafeProperties.add(
                        "app.ai-model-secrets.active-key (must be Base64-encoded 256-bit key material)");
            }
            if (hasTextProperty(environment, "app.ai-model-secrets.fingerprint-key")
                    && fingerprintKey == null) {
                unsafeProperties.add(
                        "app.ai-model-secrets.fingerprint-key (must be Base64-encoded 256-bit key material)");
            }
            if (activeKey != null && fingerprintKey != null
                    && MessageDigest.isEqual(activeKey, fingerprintKey)) {
                unsafeProperties.add(
                        "app.ai-model-secrets.active-key/fingerprint-key (key purposes must be separated)");
            }
        } finally {
            if (activeKey != null) {
                Arrays.fill(activeKey, (byte) 0);
            }
            if (fingerprintKey != null) {
                Arrays.fill(fingerprintKey, (byte) 0);
            }
        }
    }

    private byte[] decodeBase64Key(String encoded) {
        if (!hasTextValue(encoded) || encoded.contains("${")) {
            return null;
        }
        byte[] decoded;
        try {
            try {
                decoded = Base64.getDecoder().decode(encoded.trim());
            } catch (IllegalArgumentException standardFailure) {
                decoded = Base64.getUrlDecoder().decode(encoded.trim());
            }
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }
        if (decoded.length == 32) {
            return decoded;
        }
        Arrays.fill(decoded, (byte) 0);
        return null;
    }

    private void validateSecret(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties,
            String propertyName
    ) {
        validateSecret(environment, unsafeProperties, propertyName, MINIMUM_PRODUCTION_SECRET_LENGTH);
    }

    private void validateSecret(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties,
            String propertyName,
            int minimumLength
    ) {
        if (!hasTextProperty(environment, propertyName)) {
            return;
        }
        String value = readProperty(environment, propertyName);
        String normalized = normalize(value);
        if (value.trim().length() < minimumLength
                || PROHIBITED_PRODUCTION_SECRETS.contains(normalized)) {
            unsafeProperties.add(
                    propertyName + "（必须由 Secret 注入，至少 " + minimumLength
                            + " 个字符且不得使用常见默认值）"
            );
        }
    }

    private void validatePublicEndpointPolicy(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties
    ) {
        String allowedOrigins = readProperty(environment, "app.cors.allowed-origins");
        if (hasTextProperty(environment, "app.cors.allowed-origins")
                && containsUnsafeProductionOrigin(allowedOrigins)) {
            unsafeProperties.add(
                    "app.cors.allowed-origins（生产环境必须使用无通配符、非 loopback 的 HTTPS Origin）"
            );
        }
        String deployHost = readProperty(environment, "code.deploy-host");
        if (hasTextProperty(environment, "code.deploy-host") && isLoopbackEndpoint(deployHost)) {
            unsafeProperties.add(
                    "code.deploy-host（生产环境不得使用 localhost 或 loopback 地址）"
            );
        }
    }

    private boolean containsUnsafeProductionOrigin(String origins) {
        if (!hasTextValue(origins)) {
            return true;
        }
        String[] values = origins.split(",", -1);
        if (values.length == 0) {
            return true;
        }
        for (String value : values) {
            if (isUnsafeProductionOrigin(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnsafeProductionOrigin(String value) {
        if (!hasTextValue(value) || value.contains("*")) {
            return true;
        }
        try {
            URI origin = new URI(value.trim());
            return !"https".equalsIgnoreCase(origin.getScheme())
                    || !hasTextValue(origin.getHost())
                    || origin.getUserInfo() != null
                    || hasTextValue(origin.getPath()) && !"/".equals(origin.getPath())
                    || origin.getQuery() != null
                    || origin.getFragment() != null
                    || isLoopbackHost(origin.getHost());
        } catch (URISyntaxException invalidOrigin) {
            return true;
        }
    }

    private boolean isLoopbackEndpoint(String value) {
        if (!hasTextValue(value)) {
            return false;
        }
        try {
            URI endpoint = new URI(value.trim());
            return isLoopbackHost(endpoint.getHost());
        } catch (URISyntaxException invalidEndpoint) {
            return false;
        }
    }

    private boolean isLoopbackHost(String host) {
        if (!hasTextValue(host)) {
            return false;
        }
        String normalizedHost = normalize(host);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        return "localhost".equals(normalizedHost)
                || normalizedHost.endsWith(".localhost")
                || normalizedHost.startsWith("127.")
                || "::1".equals(normalizedHost)
                || "0:0:0:0:0:0:0:1".equals(normalizedHost);
    }

    private void validateDevServerInternalRouting(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties
    ) {
        String template = readProperty(environment, "app.dev-server.internal-routing.base-url-template");
        if (hasTextValue(template)
                && (!template.contains("{nodeId}")
                || !(template.startsWith("http://") || template.startsWith("https://")))) {
            unsafeProperties.add(
                    "app.dev-server.internal-routing.base-url-template"
                            + "（必须是包含 {nodeId} 的 HTTP(S) 内网地址模板）"
            );
        }
        String sharedSecret = readProperty(environment, "app.dev-server.internal-routing.shared-secret");
        if (hasTextValue(sharedSecret) && sharedSecret.trim().length() < 32) {
            unsafeProperties.add(
                    "app.dev-server.internal-routing.shared-secret（必须至少包含 32 个字符）"
            );
        }
    }

    private void validateDevServerNodeIdentity(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties
    ) {
        String nodeId = readProperty(environment, "app.dev-server.runtime.node-id");
        if (hasTextValue(nodeId)
                && !nodeId.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            unsafeProperties.add(
                    "app.dev-server.runtime.node-id（必须是可安全路由的稳定节点标识）"
            );
        }
    }

    private void validateContainerSandboxSupplyChainAndNetworks(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties
    ) {
        String image = readProperty(environment, "app.generated-code-sandbox.container.image");
        if (hasTextValue(image)
                && !image.trim().matches("[^\\s@]+@sha256:[a-fA-F0-9]{64}")) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.container.image（生产环境必须使用 sha256 digest 固定镜像）"
            );
        }
        validatePnpmStoreConfiguration(environment, unsafeProperties);
        String dependencyNetwork = normalize(readProperty(
                environment,
                "app.generated-code-sandbox.container.dependency-network"
        ));
        String devServerNetwork = normalize(readProperty(
                environment,
                "app.generated-code-sandbox.container.dev-server-network"
        ));
        String previewGatewayNetwork = normalize(readProperty(
                environment,
                "app.generated-code-sandbox.container.preview-gateway-network"
        ));
        if ("bridge".equals(dependencyNetwork)) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.container.dependency-network"
                            + "（生产环境必须使用专用依赖出口网络）"
            );
        }
        if ("bridge".equals(devServerNetwork)
                || dependencyNetwork != null && dependencyNetwork.equals(devServerNetwork)) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.container.dev-server-network"
                            + "（必须使用与依赖出口隔离的 internal 网络）"
            );
        }
        if ("bridge".equals(previewGatewayNetwork)
                || previewGatewayNetwork != null && (
                previewGatewayNetwork.equals(devServerNetwork)
                        || previewGatewayNetwork.equals(dependencyNetwork))) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.container.preview-gateway-network"
                            + "（必须使用独立的预览入口网络）"
            );
        }
    }

    private void validatePnpmStoreConfiguration(
            ConfigurableEnvironment environment,
            List<String> unsafeProperties
    ) {
        String volume = readProperty(
                environment,
                "app.generated-code-sandbox.container.pnpm-store-volume"
        );
        if (hasTextValue(volume)
                && !volume.trim().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.container.pnpm-store-volume"
                            + "（必须是预创建的安全 Docker named volume 名称）"
            );
        }

        String workspaceMount = readProperty(
                environment,
                "app.generated-code-sandbox.container.workspace-mount"
        );
        String storeMount = readProperty(
                environment,
                "app.generated-code-sandbox.container.pnpm-store-mount"
        );
        if (hasTextValue(workspaceMount) && !isSafeContainerMount(workspaceMount)) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.container.workspace-mount"
                            + "（必须是安全的绝对容器路径）"
            );
        }
        if (hasTextValue(storeMount)
                && (!isSafeContainerMount(storeMount)
                || containerPathsOverlap(storeMount, workspaceMount)
                || containerPathsOverlap(storeMount, "/tmp"))) {
            unsafeProperties.add(
                    "app.generated-code-sandbox.container.pnpm-store-mount"
                            + "（必须是与 workspace 和 /tmp 隔离的安全绝对容器路径）"
            );
        }
    }

    private boolean isSafeContainerMount(String value) {
        if (!hasTextValue(value)) {
            return false;
        }
        String path = value.trim();
        if (!path.startsWith("/")
                || "/".equals(path)
                || path.endsWith("/")
                || path.contains("//")
                || path.contains("\\")
                || path.contains(",")
                || path.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (String segment : path.substring(1).split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private boolean containerPathsOverlap(String first, String second) {
        if (!isSafeContainerMount(first) || !isSafeContainerMount(second)) {
            return false;
        }
        String normalizedFirst = first.trim();
        String normalizedSecond = second.trim();
        return normalizedFirst.equals(normalizedSecond)
                || normalizedFirst.startsWith(normalizedSecond + "/")
                || normalizedSecond.startsWith(normalizedFirst + "/");
    }

    private boolean hasTextProperty(ConfigurableEnvironment environment, String propertyName) {
        String value = readProperty(environment, propertyName);
        return hasTextValue(value) && !value.contains("${");
    }

    private boolean hasTextValue(String value) {
        return value != null && !value.isBlank();
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
