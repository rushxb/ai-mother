package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTelemetryRoutingPolicyTest {

    @Test
    void highFailureHistoryShouldEscalateExistingWorkspaceToExpertMode() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        GenerationTelemetryRoutingPolicy policy = new GenerationTelemetryRoutingPolicy(properties);
        GenerationRoutingSignal signal = signal(new GenerationRoutingTelemetrySnapshot(
                6, 4, 240_000L, 0, 0, 0,
                0, 1, 0, 4, 32, Instant.now(), true
        ));

        GenerationModeDecision decision = policy.decide(signal).orElseThrow();

        assertEquals(GenerationMode.HEAVY_EXPERT, decision.mode());
        assertEquals(ExpectedValidationLevel.EXPERT, decision.expectedValidationLevel());
        assertChineseMessage(decision.reason());
    }

    @Test
    void saturatedCapacityShouldUseChineseContainmentReason() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        GenerationTelemetryRoutingPolicy policy = new GenerationTelemetryRoutingPolicy(properties);
        GenerationRoutingSignal signal = signal(
                new GenerationRoutingTelemetrySnapshot(
                        1, 0, 700_000L, 0, 0, 0,
                        0, 4, 0, 4, 32, Instant.now(), true
                ),
                substantialEditProfile()
        );

        GenerationModeDecision decision = policy.decide(signal).orElseThrow();

        assertEquals(GenerationMode.AGENT_EDIT, decision.mode());
        assertChineseMessage(decision.reason());
    }

    @Test
    void saturatedCapacityMustUseStructuredScenarioComplexity() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        GenerationTelemetryRoutingPolicy policy = new GenerationTelemetryRoutingPolicy(properties);
        GenerationRoutingTelemetrySnapshot telemetry = new GenerationRoutingTelemetrySnapshot(
                1, 0, 700_000L, 0, 0, 0,
                0, 4, 0, 4, 32, Instant.now(), true
        );
        GenerationRoutingSignal signal = signal(telemetry, substantialEditProfile());

        GenerationModeDecision decision = policy.decide(signal).orElseThrow();

        assertEquals(GenerationMode.AGENT_EDIT, decision.mode());
    }

    @Test
    void insufficientSamplesShouldNotChangeBaseRouting() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        GenerationTelemetryRoutingPolicy policy = new GenerationTelemetryRoutingPolicy(properties);
        GenerationRoutingSignal signal = signal(new GenerationRoutingTelemetrySnapshot(
                2, 2, 240_000L, 1, 1, 1.0,
                0, 1, 0, 4, 32, Instant.now(), true
        ));

        Optional<GenerationModeDecision> decision = policy.decide(signal);

        assertTrue(decision.isEmpty());
    }

    @Test
    void applicationQualityHistoryMustNotEscalateClearlyLightweightEdit() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        GenerationTelemetryRoutingPolicy policy = new GenerationTelemetryRoutingPolicy(properties);
        GenerationRoutingSignal base = signal(new GenerationRoutingTelemetrySnapshot(
                6, 4, 240_000L, 0, 0, 0,
                0, 1, 0, 4, 32, Instant.now(), true));
        IntentProfile lightweight = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.LOW,
                false,
                false,
                IntentDestructiveRisk.LOW,
                1,
                IntentValidationRisk.LOW,
                0.95
        );
        GenerationRoutingSignal signal = new GenerationRoutingSignal(
                base.codeGenType(), base.workspaceExists(), base.telemetry(), lightweight);

        assertTrue(policy.decide(signal).isEmpty());
    }

    private GenerationRoutingSignal signal(GenerationRoutingTelemetrySnapshot telemetry) {
        return signal(telemetry, substantialEditProfile());
    }

    private GenerationRoutingSignal signal(GenerationRoutingTelemetrySnapshot telemetry,
                                           IntentProfile profile) {
        Path root = Path.of("target/test-routing-workspace");
        GenerationWorkspace workspace = new GenerationWorkspace(
                10L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                root,
                Set.of(),
                Set.of()
        );
        return GenerationRoutingSignal.from(
                CodeGenTypeEnum.VUE_PROJECT, workspace, telemetry, profile);
    }

    private IntentProfile substantialEditProfile() {
        return new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.BACKEND),
                IntentSemanticComplexity.HIGH,
                true,
                false,
                IntentDestructiveRisk.LOW,
                6,
                IntentValidationRisk.MEDIUM,
                0.95
        );
    }

    private void assertChineseMessage(String message) {
        assertTrue(message != null && message.matches(".*[\\u4e00-\\u9fff].*"),
                () -> "用户可见文案必须包含中文: " + message);
    }
}
