package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.AiAgentProductivityProperties;
import com.rush.rushaicodemother.config.AiModelCapacityProperties;
import com.rush.rushaicodemother.config.AiModelCircuitBreakerProperties;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.config.AiModelSecretProperties;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import com.rush.rushaicodemother.config.AiToolApprovalProperties;
import com.rush.rushaicodemother.config.AiToolLoopGuardProperties;
import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.config.AppDatabaseResourceProperties;
import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.config.ChatMemoryProperties;
import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.config.DevServerInternalRoutingProperties;
import com.rush.rushaicodemother.config.DevServerProxyProperties;
import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.config.DevServerWebSocketProxyProperties;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.config.ExternalProcessProperties;
import com.rush.rushaicodemother.config.GeneratedCodeSandboxProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.config.GenerationCreditReservationProperties;
import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.config.GenerationProjectContextProperties;
import com.rush.rushaicodemother.config.GenerationSseProperties;
import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.config.GenerationWorkingMemoryProperties;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.config.RedisCacheProperties;
import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.config.TemplateMaterializationProperties;
import com.rush.rushaicodemother.config.TemplatePreWarmProperties;
import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.edit.EditStatePersistenceProperties;
import com.rush.rushaicodemother.orchestration.dag.GenerationTaskSnapshotProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressProperties;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 根据实际生效的生成语义配置计算稳定指纹，不信任外部手工标签。 */
@Component
public class GenerationRuntimeConfigurationFingerprintService {

    private static final String SCHEMA = "generation-runtime-configuration-v4|";
    private static final String APPLICATION_CONFIG = "classpath:application.yml";

    /**
     * 仍然由 yaml 提供、且会影响生成结果的配置前缀。
     *
     * <p>大量内部策略已下沉为常量，改由 {@link #HARDCODED_POLICY_SOURCES} 反射纳入指纹；
     * 这里只保留仍可外部覆盖的部分。</p>
     */
    private static final List<String> INCLUDED_PREFIXES = List.of(
            "app.node-toolchain.",
            "app.go-toolchain."
    );

    private static final Set<String> INCLUDED_PROPERTIES = Set.of(
            "app.memory.long-term.enabled",
            "app.ai-model-capacity.enabled",
            "app.ai-model-capacity.fail-open",
            "app.ai-model-runtime.local-first-heavy-routing-enabled",
            "app.ai-model-runtime.first-token-hedge-enabled",
            "app.generated-code-sandbox.mode",
            "app.generated-code-sandbox.container.image",
            "app.generated-code-sandbox.container.user",
            "app.generated-code-sandbox.container.memory",
            "app.generated-code-sandbox.container.tmpfs-size",
            "app.generated-code-sandbox.container.go-build-tmpfs-size",
            "app.generated-code-sandbox.container.cpus",
            "app.generated-code-sandbox.container.pids-limit",
            "app.generated-code-sandbox.container.read-only-root",
            "app.generation-routing.shadow.enabled",
            "app.generation-memory-context.parallel-reads-enabled",
            "app.generation-memory-context.preparation-overlap-enabled",
            "app.generation-task-snapshot.replay-safe-start-checkpoint-elision-enabled",
            "app.generation-task-snapshot.replay-safe-completion-checkpoint-coalescing-enabled",
            "app.generation-event-stream.delta-coalescing-enabled",
            "app.generation-benchmark.backend-grading.port-range-start",
            "app.generation-benchmark.backend-grading.port-range-end"
    );

    private static final Set<String> EXCLUDED_PROPERTIES = Set.of(
            "app.ai-model-runtime.generation-log-requests",
            "app.ai-model-runtime.generation-log-responses",
            "app.ai-model-runtime.routing-log-requests",
            "app.ai-model-runtime.routing-log-responses",
            "app.generation-benchmark.browser-grading.enabled",
            "app.generation-benchmark.backend-grading.enabled",
            "app.generation-benchmark.backend-grading.workspace-root"
    );

    /**
     * 生成链路的固定策略载体。
     *
     * <p>这些类的生效取值来自 {@code public static final} 常量而不是 yaml，因此必须反射收集后
     * 并入发布指纹；否则调整常量就能绕过发布门禁。新增策略类只需登记到此列表，
     * 其全部公开常量会自动纳入指纹。</p>
     */
    private static final List<Class<?>> HARDCODED_POLICY_SOURCES = List.of(
            GenerationProjectContextProperties.class,
            EditLocatorProperties.class,
            PatchExecutionProperties.class,
            AiToolWorkspaceProperties.class,
            AiToolLoopGuardProperties.class,
            AiAgentProductivityProperties.class,
            AppDatabaseResourceProperties.class,
            AiContextPackBudgetProperties.class,
            AiModelCapacityProperties.class,
            AiModelCircuitBreakerProperties.class,
            AiModelRuntimeProperties.class,
            AiModelSecretProperties.class,
            AiPromptCatalogProperties.class,
            AiToolApprovalProperties.class,
            ArtifactLifecycleProperties.class,
            ChatMemoryProperties.class,
            DependencyInstallProperties.class,
            DevServerInternalRoutingProperties.class,
            DevServerProxyProperties.class,
            DevServerRuntimeProperties.class,
            DevServerWebSocketProxyProperties.class,
            EditStatePersistenceProperties.class,
            ExternalProcessProperties.class,
            GeneratedCodeSandboxProperties.Container.class,
            GenerationBenchmarkBackendProperties.class,
            GenerationBenchmarkBrowserProperties.class,
            GenerationBenchmarkEvidenceProperties.class,
            GenerationBenchmarkReleaseProperties.class,
            GenerationBenchmarkWorkerProperties.class,
            GenerationCommitProperties.class,
            GenerationCreditReservationProperties.class,
            GenerationEventStreamProperties.class,
            GenerationMemoryContextProperties.class,
            GenerationMemoryOutboxProperties.class,
            GenerationRoutingTelemetryProperties.class,
            GenerationRuntimeProperties.class,
            GenerationSessionProperties.class,
            GenerationSlaProperties.class,
            GenerationSseProperties.class,
            GenerationStageAdmissionProperties.class,
            GenerationTaskAdmissionProperties.class,
            GenerationTaskExecutorProperties.class,
            GenerationTaskLeaseProperties.class,
            GenerationTaskProgressProperties.class,
            GenerationTaskQueueProperties.class,
            GenerationTaskSnapshotProperties.class,
            GenerationWorkingMemoryProperties.class,
            MilvusMemoryProperties.class,
            ProjectCommandProperties.class,
            RateLimiterProperties.class,
            RedisCacheProperties.class,
            ScreenshotProperties.class,
            TemplateMaterializationProperties.class,
            TemplatePreWarmProperties.class,
            UserCreditProperties.class,
            WorkspaceFileSystemProperties.class
    );

    /** 固定策略常量前缀，用于与 yaml 属性名区分。 */
    private static final String HARDCODED_POLICY_PREFIX = "internal.policy.";

    /** 固定策略覆盖摘要在指纹中的字段名。 */
    private static final String POLICY_OVERRIDE_FIELD = "internal.policy-override-digest";

    private static final Map<String, String> HARDCODED_POLICY_VALUES = hardcodedPolicySignatures();

    /**
     * 常量支撑字段对应的可覆盖属性名。
     *
     * <p>键为属性名（如 {@code app.ai-model-capacity.max-concurrent-per-model}），值为其所属常量的
     * 指纹键，便于诊断。仅收录「既登记为固定策略、又保留外部绑定」的字段 —— 其余策略类无绑定入口，
     * 不存在覆盖风险。</p>
     */
    private static final Map<String, String> POLICY_BOUND_PROPERTY_NAMES = policyBoundPropertyNames();

    private final ConfigurableEnvironment environment;
    private final Map<String, String> configuredDefaults;

    public GenerationRuntimeConfigurationFingerprintService(
            ConfigurableEnvironment environment,
            ResourceLoader resourceLoader
    ) {
        this.environment = environment;
        this.configuredDefaults = discoverConfiguration(resourceLoader);
    }

    /**
 * 返回当前指纹。
 *
 * @return 处理后的生成运行时配置指纹文本
 */
    public String currentFingerprint() {
        StringBuilder canonical = new StringBuilder(SCHEMA);
        configuredDefaults.forEach((name, defaultValue) -> {
            ReleaseCandidateFingerprint.appendField(canonical, name);
            ReleaseCandidateFingerprint.appendField(
                    canonical,
                    resolveValue(name, defaultValue)
            );
        });
        ReleaseCandidateFingerprint.appendField(canonical, POLICY_OVERRIDE_FIELD);
        ReleaseCandidateFingerprint.appendField(canonical, policyOverrideDigest());
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }

    /**
     * 汇总「固定策略字段被外部属性覆盖」的实际情况。
     *
     * <p>部分策略类仍保留 {@code @ConfigurationProperties}，因为同一个类里还有必须由外部注入的
     * 运维项（主机名、键前缀、开关）。这带来一个风险：常量支撑的字段也能被同前缀属性覆盖，
     * 而指纹只按常量计算，于是「改了生效行为但指纹不变」，发布门禁被绕过。</p>
     *
     * <p>此处不阻止覆盖，而是让覆盖必然改变指纹：逐个探测策略字段对应的属性名，把实际存在的
     * 覆盖以 {@code 属性名=值} 的规范化形式并入指纹。未被覆盖时摘要为固定的
     * {@code none}，因此正常部署的指纹不受影响。</p>
     *
     * @return 覆盖摘要；无覆盖时返回 {@code none}
     */
    private String policyOverrideDigest() {
        StringBuilder overrides = new StringBuilder();
        // TreeMap 保证与探测顺序无关，指纹对属性声明次序稳定。
        new TreeMap<>(POLICY_BOUND_PROPERTY_NAMES).forEach((propertyName, ignored) -> {
            String overridden = environment.getProperty(propertyName);
            if (overridden == null) {
                return;
            }
            ReleaseCandidateFingerprint.appendField(overrides, propertyName);
            ReleaseCandidateFingerprint.appendField(overrides, overridden.trim());
        });
        return overrides.isEmpty() ? "none" : ReleaseCandidateFingerprint.sha256(overrides.toString());
    }

    /** 返回{@code discover}配置。 */
    private Map<String, String> discoverConfiguration(ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(APPLICATION_CONFIG);
        Map<String, String> discovered = new TreeMap<>(HARDCODED_POLICY_VALUES);
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("generation-runtime-fingerprint", resource)) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    if (isIncluded(name)) {
                        Object value = source.getProperty(name);
                        discovered.put(name, value == null ? "" : value.toString());
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取生成运行配置定义", failure);
        }
        if (discovered.isEmpty()) {
            throw new IllegalStateException("生成运行配置指纹清单为空");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(discovered));
    }

    private boolean isIncluded(String name) {
        if (EXCLUDED_PROPERTIES.contains(name)) {
            return false;
        }
        return INCLUDED_PROPERTIES.contains(name)
                || INCLUDED_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /**
     * 根据当前上下文解析值。
     *
     * <p>固定策略常量不读取 environment：它们的生效取值来自常量本身。为防止「策略类仍保留
     * {@code @ConfigurationProperties} 绑定、外部属性覆盖了实例字段而指纹不变」这一门禁绕过，
     * 覆盖检测由 {@link #policyOverrideDigest()} 单独并入指纹，见其说明。</p>
     */
    private String resolveValue(String name, String defaultValue) {
        if (HARDCODED_POLICY_VALUES.containsKey(name)) {
            return defaultValue;
        }
        String value = environment.getProperty(name);
        String candidate = value == null ? defaultValue : value;
        try {
            return environment.resolveRequiredPlaceholders(candidate).trim();
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalStateException("生成运行配置存在未解析的占位符: " + name, unresolved);
        }
    }

    public static Map<String, String> hardcodedPolicyValues() {
        return HARDCODED_POLICY_VALUES;
    }

    /** 供发布门禁校验固定策略载体清单，避免新增策略类漏登记或残留外部绑定。 */
    public static List<Class<?>> hardcodedPolicySources() {
        return HARDCODED_POLICY_SOURCES;
    }

    /**
     * 反射收集所有固定策略常量。
     *
     * <p>键名形如 {@code internal.policy.<类名>.<常量名>}，保证与 yaml 属性名不冲突，
     * 并且按 {@link TreeMap} 排序，使指纹与声明顺序无关。</p>
     */
    private static Map<String, String> hardcodedPolicySignatures() {
        Map<String, String> signatures = new TreeMap<>();
        for (Class<?> source : HARDCODED_POLICY_SOURCES) {
            for (Field field : source.getDeclaredFields()) {
                if (!isPublicPolicyConstant(field)) {
                    continue;
                }
                signatures.put(
                        HARDCODED_POLICY_PREFIX + source.getSimpleName() + "." + field.getName(),
                        readConstant(source, field));
            }
        }
        if (signatures.isEmpty()) {
            throw new IllegalStateException("固定生成策略常量清单为空");
        }
        return Collections.unmodifiableMap(signatures);
    }

    /**
     * 反射推导常量支撑字段对应的可覆盖属性名。
     *
     * <p>判定方式：策略类带 {@code @ConfigurationProperties} 时，若某个公开常量存在同名实例字段
     * （按 Spring 宽松绑定规则比较，忽略下划线与大小写），则该字段可被
     * {@code <prefix>.<kebab-case 字段名>} 覆盖。</p>
     */
    private static Map<String, String> policyBoundPropertyNames() {
        Map<String, String> propertyNames = new TreeMap<>();
        for (Class<?> source : HARDCODED_POLICY_SOURCES) {
            ConfigurationProperties binding = source.getAnnotation(ConfigurationProperties.class);
            if (binding == null) {
                continue;
            }
            String prefix = binding.prefix();
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            for (Field constant : source.getDeclaredFields()) {
                if (!isPublicPolicyConstant(constant)) {
                    continue;
                }
                String instanceFieldName = boundInstanceFieldName(source, constant.getName());
                if (instanceFieldName == null) {
                    continue;
                }
                propertyNames.put(
                        prefix + "." + kebabCase(instanceFieldName),
                        HARDCODED_POLICY_PREFIX + source.getSimpleName() + "." + constant.getName());
            }
        }
        return Collections.unmodifiableMap(propertyNames);
    }

    /** 返回以该常量作为默认值的实例字段名；不存在则返回 {@code null}。 */
    private static String boundInstanceFieldName(Class<?> source, String constantName) {
        String normalizedConstant = constantName.replace("_", "").toLowerCase(Locale.ROOT);
        for (Field candidate : source.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers())) {
                continue;
            }
            if (candidate.getName().toLowerCase(Locale.ROOT).equals(normalizedConstant)) {
                return candidate.getName();
            }
        }
        return null;
    }

    /** 把驼峰字段名转换为 Spring 规范属性名。 */
    private static String kebabCase(String fieldName) {
        StringBuilder kebab = new StringBuilder(fieldName.length() + 8);
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0) {
                    kebab.append('-');
                }
                kebab.append(Character.toLowerCase(character));
                continue;
            }
            kebab.append(character);
        }
        return kebab.toString();
    }

    /** 供发布门禁校验覆盖探测清单是否覆盖了全部仍可绑定的策略字段。 */
    public static Map<String, String> policyBoundProperties() {
        return POLICY_BOUND_PROPERTY_NAMES;
    }

    /** 判断字段是否为参与指纹的公开策略常量。 */
    private static boolean isPublicPolicyConstant(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isPublic(modifiers)
                && Modifier.isStatic(modifiers)
                && Modifier.isFinal(modifiers);
    }

    /** 读取常量取值并转换为稳定字符串。 */
    private static String readConstant(Class<?> source, Field field) {
        try {
            return String.valueOf(field.get(null));
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalStateException(
                    "无法读取固定生成策略常量: " + source.getSimpleName() + "." + field.getName(),
                    inaccessible);
        }
    }
}
