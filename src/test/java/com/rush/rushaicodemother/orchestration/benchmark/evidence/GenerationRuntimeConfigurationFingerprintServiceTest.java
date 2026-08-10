package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.AiToolLoopGuardProperties;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRuntimeConfigurationFingerprintServiceTest {

    private MockEnvironment environment;
    private GenerationRuntimeConfigurationFingerprintService service;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        service = new GenerationRuntimeConfigurationFingerprintService(
                environment,
                new DefaultResourceLoader()
        );
    }

    @Test
    void externallyConfigurableGenerationSettingsMustChangeFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.generated-code-sandbox.container.cpus", "3.0");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void toolchainAndSandboxSupplyChainMustEnterFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.node-toolchain.pnpm-executable", "/opt/pnpm");
        String toolchainChanged = service.currentFingerprint();
        assertNotEquals(baseline, toolchainChanged);

        environment.setProperty(
                "app.generated-code-sandbox.container.image",
                "registry.example.com/sandbox@sha256:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        assertNotEquals(toolchainChanged, service.currentFingerprint());
    }

    @Test
    void routingAndMemoryOverlapTogglesMustEnterFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.generation-routing.shadow.enabled", "true");
        String shadowChanged = service.currentFingerprint();
        assertNotEquals(baseline, shadowChanged);

        environment.setProperty("app.generation-memory-context.parallel-reads-enabled", "true");
        assertNotEquals(shadowChanged, service.currentFingerprint());
    }

    @Test
    void replaySafeCheckpointTogglesMustEnterFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-task-snapshot.replay-safe-start-checkpoint-elision-enabled",
                "true"
        );
        String elisionChanged = service.currentFingerprint();
        assertNotEquals(baseline, elisionChanged);

        environment.setProperty(
                "app.generation-task-snapshot.replay-safe-completion-checkpoint-coalescing-enabled",
                "true"
        );
        assertNotEquals(elisionChanged, service.currentFingerprint());
    }

    @Test
    void fullySunkPolicyOverridesMustBeIgnored() {
        String baseline = service.currentFingerprint();

        // 这些策略类已完全去除 @ConfigurationProperties，外部注入无绑定入口，
        // 既不改变生效配置也不改变指纹。
        environment.setProperty("app.ai-tool-loop-guard.max-no-progress-calls", "7");
        environment.setProperty(
                "app.ai-agent-productivity.max-model-turns-without-mutation", "4");
        environment.setProperty("app.ai-tool-workspace.max-batch-write-files", "12");
        environment.setProperty("app.generation-runtime.max-repair-rounds", "9");
        environment.setProperty("app.generation-sla.profiles.CREATE.max-model-turns", "40");
        environment.setProperty("app.project-command.full-build-timeout", "9m");
        environment.setProperty("app.workspace-file-system.max-files", "5");
        environment.setProperty(
                "app.generation-benchmark.release-gate.minimum-success-rate", "0.1");
        environment.setProperty("app.ai-context-pack.generation-max-tokens", "1234");

        assertEquals(baseline, service.currentFingerprint());
    }

    @Test
    void stillBindablePolicyOverridesMustChangeFingerprint() {
        String baseline = service.currentFingerprint();

        // 这两个类仍保留 @ConfigurationProperties（同类中另有必须外部注入的运维项），
        // 因此常量支撑字段实际可被覆盖。覆盖会改变生效行为，指纹必须随之变化，
        // 否则「改了行为、指纹不变」会绕过发布门禁。
        environment.setProperty("app.chat-memory.completed-tool-arguments-max-chars", "4096");
        String chatMemoryOverridden = service.currentFingerprint();
        assertNotEquals(baseline, chatMemoryOverridden);

        environment.setProperty("app.generation-event-stream.delta-max-chars", "4096");
        assertNotEquals(chatMemoryOverridden, service.currentFingerprint());
    }

    @Test
    void secretsAndRoleSwitchesMustNotEnterFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-model-secrets.active-key", "secret-value");
        environment.setProperty("app.ai-model-runtime.generation-log-requests", "true");
        environment.setProperty(
                "app.generation-benchmark.backend-grading.workspace-root",
                "/machine-specific/runtime-root"
        );
        environment.setProperty("app.generation-benchmark.browser-grading.enabled", "true");
        environment.setProperty("app.generation-benchmark.backend-grading.enabled", "true");

        assertEquals(baseline, service.currentFingerprint());
    }

    @Test
    void hardcodedPoliciesMustRemainPartOfTheReleaseFingerprintInput() {
        Map<String, String> values =
                GenerationRuntimeConfigurationFingerprintService.hardcodedPolicyValues();

        // 常量清单通过反射收集，逐类抽样校验代表性策略取值。
        assertEquals(String.valueOf(EditLocatorProperties.MAX_CANDIDATE_FILES),
                values.get("internal.policy.EditLocatorProperties.MAX_CANDIDATE_FILES"));
        assertEquals(String.valueOf(AiToolLoopGuardProperties.MAX_NO_PROGRESS_CALLS),
                values.get("internal.policy.AiToolLoopGuardProperties.MAX_NO_PROGRESS_CALLS"));
        assertEquals(String.valueOf(AiToolLoopGuardProperties.RETENTION),
                values.get("internal.policy.AiToolLoopGuardProperties.RETENTION"));
        assertEquals(String.valueOf(GenerationSlaProperties.CREATE_MAX_MODEL_TURNS),
                values.get("internal.policy.GenerationSlaProperties.CREATE_MAX_MODEL_TURNS"));
    }

    @Test
    void everyHardcodedPolicyClassMustContributeToTheFingerprint() {
        Map<String, String> values =
                GenerationRuntimeConfigurationFingerprintService.hardcodedPolicyValues();

        // 覆盖面回归：下沉的策略类必须全部登记，避免调整常量绕过发布门禁。
        for (String policyClass : new String[]{
                "AgentConversationWindowPolicy",
                "GenerationProjectContextProperties", "EditLocatorProperties",
                "PatchExecutionProperties", "AiToolWorkspaceProperties",
                "AiToolLoopGuardProperties", "AiAgentProductivityProperties",
                "AiContextPackBudgetProperties", "AiModelCapacityProperties",
                "AiModelCircuitBreakerProperties", "AiModelRuntimeProperties",
                "ArtifactLifecycleProperties", "DependencyInstallProperties",
                "DevServerRuntimeProperties", "EditStatePersistenceProperties",
                "GenerationBenchmarkBackendProperties", "GenerationBenchmarkReleaseProperties",
                "GenerationCommitProperties", "GenerationCreditReservationProperties",
                "GenerationEventStreamProperties", "GenerationRoutingTelemetryProperties",
                "GenerationRuntimeProperties", "GenerationSlaProperties",
                "GenerationStageAdmissionProperties", "GenerationTaskExecutorProperties",
                "GenerationTaskProgressProperties", "GenerationTaskQueueProperties",
                "GenerationTaskSnapshotProperties", "MilvusMemoryProperties",
                "ProjectCommandProperties", "RateLimiterProperties",
                "ScreenshotProperties", "TemplateMaterializationProperties",
                "WorkspaceFileSystemProperties"
        }) {
            String prefix = "internal.policy." + policyClass + ".";
            assertTrue(
                    values.keySet().stream().anyMatch(key -> key.startsWith(prefix)),
                    "固定策略类必须进入发布配置指纹: " + policyClass
            );
        }
    }

    @Test
    void equivalentPropertyInsertionOrderMustRemainDeterministic() {
        environment.setProperty("app.node-toolchain.node-executable", "/opt/node");
        environment.setProperty("app.generated-code-sandbox.container.cpus", "2.0");
        String first = service.currentFingerprint();

        MockEnvironment reordered = new MockEnvironment();
        reordered.setProperty("app.generated-code-sandbox.container.cpus", "2.0");
        reordered.setProperty("app.node-toolchain.node-executable", "/opt/node");
        GenerationRuntimeConfigurationFingerprintService secondService =
                new GenerationRuntimeConfigurationFingerprintService(
                        reordered,
                        new DefaultResourceLoader()
                );

        assertEquals(first, secondService.currentFingerprint());
    }
}
