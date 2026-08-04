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
                "请综合评估现有系统的模块边界、数据流转、异常处理和可扩展性，并在不改变对外行为的前提下优化内部协作方式，补充必要的边界校验、故障恢复和回归验证策略。".repeat(3)
        );

        GenerationModeDecision decision = policy.decide(signal).orElseThrow();

        assertEquals(GenerationMode.AGENT_EDIT, decision.mode());
        assertChineseMessage(decision.reason());
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
        return signal(telemetry,
                "Implement a cross-file user management feature with API and database changes");
    }

    private GenerationRoutingSignal signal(GenerationRoutingTelemetrySnapshot telemetry, String message) {
        App app = App.builder().id(10L).userId(7L).codeGenType("vue_project").build();
        User user = User.builder().id(7L).build();
        GenerationTaskRequest request = new GenerationTaskRequest(
                app,
                message,
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

    private void assertChineseMessage(String message) {
        assertTrue(message != null && message.matches(".*[\\u4e00-\\u9fff].*"),
                () -> "用户可见文案必须包含中文: " + message);
    }
}
