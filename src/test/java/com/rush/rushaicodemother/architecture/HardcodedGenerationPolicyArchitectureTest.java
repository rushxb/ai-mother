package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationRuntimeConfigurationFingerprintService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定生成策略常量的发布门禁。
 *
 * <p>大量内部策略已从 yaml 下沉为 {@code public static final} 常量，并由
 * {@link GenerationRuntimeConfigurationFingerprintService} 反射纳入发布指纹。该设计成立的前提是
 * 「常量即生效取值」；一旦某个策略类既登记为固定策略、又保留 {@link ConfigurationProperties} 绑定，
 * 外部属性就能覆盖实例字段而指纹仍按常量计算 —— 覆盖后指纹不变，发布门禁被绕过。
 * 本测试锁定这条不变量。</p>
 */
class HardcodedGenerationPolicyArchitectureTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path MAIN_RESOURCES = PROJECT_ROOT.resolve(
            Path.of("src", "main", "resources"));

    /** 固定策略常量在指纹中的键前缀。 */
    private static final String POLICY_KEY_PREFIX = "internal.policy.";

    /** 部署方仅维护这两个 yaml；其余 Profile 取值由代码常量提供。 */
    private static final List<String> RETAINED_YAML_FILES = List.of(
            "application.yml", "application-dev.yml");

    @Test
    void everyExternallyBindablePolicyFieldMustBeWatchedForOverride() {
        Map<String, String> watched =
                GenerationRuntimeConfigurationFingerprintService.policyBoundProperties();

        List<String> unwatched = new ArrayList<>();
        for (Class<?> source : policySources()) {
            ConfigurationProperties binding = source.getAnnotation(ConfigurationProperties.class);
            if (binding == null) {
                continue;
            }
            for (String constant : constantsUsedAsFieldDefaults(source)) {
                String propertyName = binding.prefix() + "." + relaxedPropertyName(constant);
                if (!watched.containsKey(propertyName)) {
                    unwatched.add(source.getSimpleName() + "." + constant
                            + " 可被 " + propertyName + " 覆盖，但未纳入覆盖探测");
                }
            }
        }

        assertTrue(unwatched.isEmpty(),
                "仍可外部覆盖的固定策略字段必须纳入指纹覆盖探测，否则覆盖后指纹不变：\n"
                        + String.join("\n", unwatched));
    }

    @Test
    void everyHardcodedPolicyConstantMustEnterTheReleaseFingerprint() {
        Map<String, String> fingerprintInput =
                GenerationRuntimeConfigurationFingerprintService.hardcodedPolicyValues();

        List<String> missing = new ArrayList<>();
        for (Class<?> source : policySources()) {
            for (Field field : source.getDeclaredFields()) {
                if (!isPublicConstant(field)) {
                    continue;
                }
                String key = POLICY_KEY_PREFIX + source.getSimpleName() + "." + field.getName();
                if (!fingerprintInput.containsKey(key)) {
                    missing.add(key);
                }
            }
        }

        assertTrue(missing.isEmpty(), "以下固定策略常量未进入发布指纹：\n" + String.join("\n", missing));
        assertFalse(fingerprintInput.isEmpty());
    }

    @Test
    void overridingAnyBindablePolicyFieldMustChangeTheReleaseFingerprint() {
        Map<String, String> watched =
                GenerationRuntimeConfigurationFingerprintService.policyBoundProperties();
        assertFalse(watched.isEmpty(), "覆盖探测清单不应为空，否则本门禁失去意义");

        MockEnvironment baselineEnvironment = new MockEnvironment();
        String baseline = new GenerationRuntimeConfigurationFingerprintService(
                baselineEnvironment, new DefaultResourceLoader()).currentFingerprint();

        List<String> ignoredOverrides = new ArrayList<>();
        for (String propertyName : watched.keySet()) {
            MockEnvironment overridden = new MockEnvironment();
            // 取值本身无需合法：门禁只验证「覆盖被指纹感知」，不验证业务约束。
            overridden.setProperty(propertyName, "policy-override-probe");
            String fingerprint = new GenerationRuntimeConfigurationFingerprintService(
                    overridden, new DefaultResourceLoader()).currentFingerprint();
            if (baseline.equals(fingerprint)) {
                ignoredOverrides.add(propertyName);
            }
        }

        assertTrue(ignoredOverrides.isEmpty(),
                "以下属性可覆盖固定策略却不改变发布指纹，构成门禁绕过：\n"
                        + String.join("\n", ignoredOverrides));
    }

    @Test
    void unchangedDeploymentMustKeepPolicyOverrideDigestStable() {
        String first = new GenerationRuntimeConfigurationFingerprintService(
                new MockEnvironment(), new DefaultResourceLoader()).currentFingerprint();
        String second = new GenerationRuntimeConfigurationFingerprintService(
                new MockEnvironment(), new DefaultResourceLoader()).currentFingerprint();

        assertEquals(first, second, "无覆盖时指纹必须稳定，避免误报发布差异");
    }

    @Test
    void fingerprintMustNotDeriveHardcodedPoliciesFromYamlPrefixes() throws Exception {
        String service = Files.readString(PROJECT_ROOT.resolve(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration",
                "benchmark", "evidence", "GenerationRuntimeConfigurationFingerprintService.java")));

        // 这些前缀的策略已完全下沉为常量，不得再从 yaml 读取，否则会出现双重事实源。
        for (String retiredPrefix : new String[]{
                "\"app.generation-project-context.\"",
                "\"app.edit-locator.\"",
                "\"app.patch-execution.\"",
                "\"app.ai-tool-workspace.\"",
                "\"app.ai-tool-loop-guard.\"",
                "\"app.ai-agent-productivity.\""
        }) {
            assertFalse(service.contains(retiredPrefix),
                    "已下沉为常量的策略前缀不得回到 yaml 指纹清单: " + retiredPrefix);
        }
    }

    @Test
    void retiredPolicyPrefixesMustNotReappearInRetainedYaml() throws Exception {
        for (String yamlFile : RETAINED_YAML_FILES) {
            Path path = MAIN_RESOURCES.resolve(yamlFile);
            assertTrue(Files.exists(path), "部署必需的 yaml 缺失: " + yamlFile);
            String yaml = Files.readString(path);
            for (String retiredKey : new String[]{
                    "generation-project-context:", "edit-locator:", "patch-execution:",
                    "ai-tool-workspace:", "ai-tool-loop-guard:", "ai-agent-productivity:"
            }) {
                assertFalse(yaml.contains(retiredKey),
                        yamlFile + " 不得恢复已下沉为常量的策略配置: " + retiredKey);
            }
        }
    }

    @Test
    void onlyApplicationAndDevYamlMayRemain() throws Exception {
        List<String> yamlFiles;
        try (var files = Files.list(MAIN_RESOURCES)) {
            yamlFiles = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml") || name.endsWith(".yaml"))
                    .sorted()
                    .toList();
        }

        assertEquals(RETAINED_YAML_FILES.stream().sorted().toList(), yamlFiles,
                "Profile 取值应由代码常量提供，仅保留 application.yml 与 application-dev.yml");
    }

    /** 返回登记在指纹服务中的全部固定策略载体。 */
    private List<Class<?>> policySources() {
        return GenerationRuntimeConfigurationFingerprintService.hardcodedPolicySources();
    }

    /**
     * 返回被同名实例字段用作默认值的公开常量。
     *
     * <p>只有这类常量才存在「外部属性覆盖后与常量不一致」的风险；纯用于校验边界或内部计算的常量
     * 不参与绑定，不构成违规。</p>
     */
    private List<String> constantsUsedAsFieldDefaults(Class<?> source) {
        List<String> bound = new ArrayList<>();
        for (Field constant : source.getDeclaredFields()) {
            if (!isPublicConstant(constant)) {
                continue;
            }
            String relaxedName = relaxedPropertyName(constant.getName());
            for (Field instanceField : source.getDeclaredFields()) {
                if (Modifier.isStatic(instanceField.getModifiers())) {
                    continue;
                }
                if (instanceField.getName().toLowerCase(Locale.ROOT).equals(relaxedName.replace("-", ""))) {
                    bound.add(constant.getName());
                    break;
                }
            }
        }
        return bound;
    }

    /** 把常量名转换为 Spring 宽松绑定使用的属性名。 */
    private String relaxedPropertyName(String constantName) {
        return constantName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private boolean isPublicConstant(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isPublic(modifiers)
                && Modifier.isStatic(modifiers)
                && Modifier.isFinal(modifiers);
    }
}
