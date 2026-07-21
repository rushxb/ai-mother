package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
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

    private GenerationRoutingSignal signal(GenerationRoutingTelemetrySnapshot telemetry) {
        App app = App.builder().id(10L).userId(7L).codeGenType("vue_project").build();
        User user = User.builder().id(7L).build();
        GenerationTaskRequest request = new GenerationTaskRequest(
                app,
                "Implement a cross-file user management feature with API and database changes",
                user
        );
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
        return GenerationRoutingSignal.from(request, CodeGenTypeEnum.VUE_PROJECT, workspace, telemetry);
    }
}
