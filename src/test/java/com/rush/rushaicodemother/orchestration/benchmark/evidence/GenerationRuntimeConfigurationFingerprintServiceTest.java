package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
    void effectiveGenerationConfigurationMustChangeFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.generation-runtime.max-repair-rounds", "9");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void streamSnapshotPolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-runtime.stream-snapshot-update-interval",
                "8s"
        );
        String intervalChanged = service.currentFingerprint();
        assertNotEquals(baseline, intervalChanged);

        environment.setProperty("app.generation-runtime.stream-snapshot-max-chars", "30000");
        assertNotEquals(intervalChanged, service.currentFingerprint());
    }

    @Test
    void sharedEventDeltaPolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.generation-event-stream.delta-coalescing-enabled", "false");
        String enabledChanged = service.currentFingerprint();
        assertNotEquals(baseline, enabledChanged);

        environment.setProperty("app.generation-event-stream.delta-flush-interval", "80ms");
        String intervalChanged = service.currentFingerprint();
        assertNotEquals(enabledChanged, intervalChanged);

        environment.setProperty("app.generation-event-stream.delta-max-chars", "4096");
        assertNotEquals(intervalChanged, service.currentFingerprint());
    }

    @Test
    void toolLoopPolicyMustEnterTheReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-tool-loop-guard.max-no-progress-calls", "7");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void agentProductivityPolicyMustEnterTheReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.ai-agent-productivity.max-model-turns-without-mutation", "4");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void batchWritePolicyMustEnterTheReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-tool-workspace.max-batch-write-files", "12");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void completedToolContextPolicyMustEnterTheReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.chat-memory.completed-tool-arguments-max-chars", "4096");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void gradingPolicyMustEnterFingerprintButRoleSwitchesMustNot() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-benchmark.backend-grading.request-timeout",
                "7s"
        );
        String backendChanged = service.currentFingerprint();
        assertNotEquals(baseline, backendChanged);

        environment.setProperty(
                "app.generation-benchmark.browser-grading.enabled",
                "true"
        );
        environment.setProperty(
                "app.generation-benchmark.backend-grading.enabled",
                "true"
        );
        assertEquals(backendChanged, service.currentFingerprint());
    }

    @Test
    void firstPreviewReleasePolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-benchmark.release-gate.maximum-p99-first-preview-latency",
                "4m"
        );

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void firstTokenHedgePolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-model-runtime.first-token-hedge-enabled", "true");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void firstSignalTimeoutMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-model-runtime.first-signal-timeout", "35s");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void localFirstHeavyRoutingPolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-model-runtime.local-first-heavy-routing-enabled", "false");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void rootModelRetryPolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-model-runtime.root-model-retry-min-delay", "2s");

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void memoryContextParallelReadPolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-memory-context.parallel-reads-enabled",
                "true"
        );

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void memoryContextPreparationOverlapPolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-memory-context.preparation-overlap-enabled",
                "true"
        );

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void replaySafeCheckpointElisionMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-task-snapshot.replay-safe-start-checkpoint-elision-enabled",
                "true"
        );

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void replaySafeCompletionCheckpointPolicyMustEnterReleaseConfigurationFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty(
                "app.generation-task-snapshot.replay-safe-completion-checkpoint-coalescing-enabled",
                "true"
        );
        String coalescingChanged = service.currentFingerprint();
        assertNotEquals(baseline, coalescingChanged);

        environment.setProperty(
                "app.generation-task-snapshot.replay-safe-completion-checkpoint-interval",
                "8"
        );
        assertNotEquals(coalescingChanged, service.currentFingerprint());
    }

    @Test
    void secretsAndLoggingFlagsMustNotEnterFingerprint() {
        String baseline = service.currentFingerprint();

        environment.setProperty("app.ai-model-secrets.active-key", "secret-value");
        environment.setProperty("app.ai-model-runtime.generation-log-requests", "true");
        environment.setProperty(
                "app.generation-benchmark.backend-grading.workspace-root",
                "/machine-specific/runtime-root"
        );

        assertEquals(baseline, service.currentFingerprint());
    }

    @Test
    void equivalentPropertyInsertionOrderMustRemainDeterministic() {
        environment.setProperty("app.patch-execution.max-operations", "77");
        environment.setProperty("app.ai-context-pack.generation-max-tokens", "1234");
        String first = service.currentFingerprint();

        MockEnvironment reordered = new MockEnvironment();
        reordered.setProperty("app.ai-context-pack.generation-max-tokens", "1234");
        reordered.setProperty("app.patch-execution.max-operations", "77");
        GenerationRuntimeConfigurationFingerprintService secondService =
                new GenerationRuntimeConfigurationFingerprintService(
                        reordered,
                        new DefaultResourceLoader()
                );

        assertEquals(first, secondService.currentFingerprint());
    }
}
