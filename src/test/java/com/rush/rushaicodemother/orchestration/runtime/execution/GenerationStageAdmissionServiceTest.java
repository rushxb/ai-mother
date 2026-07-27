package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationStageAdmissionServiceTest {

    @Test
    void modelTurnMustNotConsumeBudgetWhenCompletionWindowCannotFit() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationStageAdmissionService service = service(meterRegistry);
        GenerationExecutionContextTest.MutableClock clock =
                new GenerationExecutionContextTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GenerationExecutionContext context = context(
                "model-turn-deadline",
                Duration.ofSeconds(101),
                clock
        );

        GenerationModelTurnAdmissionException exception = assertThrows(
                GenerationModelTurnAdmissionException.class,
                () -> service.requireModelTurn(
                        context,
                        CodeGenTypeEnum.VUE_PROJECT,
                        "heavy"
                )
        );

        assertEquals(Duration.ofSeconds(72), exception.completionReserve());
        assertEquals(Duration.ofSeconds(102), exception.required());
        assertEquals(0, context.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(1.0, meterRegistry.get("generation_stage_admission_total")
                .tag("orchestration_mode", "heavy")
                .tag("stage", "model_turn")
                .tag("outcome", "reserved_completion")
                .counter()
                .count());
    }

    @Test
    void modelTurnTimeoutMustStopBeforeProtectedBuildWindow() {
        GenerationStageAdmissionService service = service(new SimpleMeterRegistry());
        GenerationExecutionContextTest.MutableClock clock =
                new GenerationExecutionContextTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GenerationExecutionContext context = context(
                "model-turn-window",
                Duration.ofMinutes(3),
                Duration.ofMinutes(3),
                clock
        );

        GenerationStageAdmissionService.ModelTurnWindow window = service.requireModelTurn(
                context,
                CodeGenTypeEnum.VUE_PROJECT,
                "heavy"
        );

        assertEquals(Duration.ofSeconds(108), window.timeout());
        assertEquals(Duration.ofSeconds(72), window.completionReserve());
        assertEquals(Duration.ofSeconds(102), window.minimumRequired());
        assertTrue(window.completionWindowLimited());
        assertEquals(1, context.used(GenerationBudgetKind.MODEL_TURN));
    }

    @Test
    void repairIsSkippedBeforeBudgetConsumptionWhenFullCycleCannotFit() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationStageAdmissionService service = service(meterRegistry);
        GenerationExecutionContextTest.MutableClock clock =
                new GenerationExecutionContextTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GenerationPreparation preparation = preparation("repair-deadline");
        GenerationSession session = new GenerationSession(
                preparation,
                context("repair-deadline", Duration.ofMinutes(3), clock)
        );
        clock.advance(Duration.ofSeconds(61));

        assertFalse(service.allowRepair(session, preparation, "heavy", "build"));
        assertEquals(0, session.executionContext().used(GenerationBudgetKind.REPAIR_ROUND));
        assertEquals(1.0, meterRegistry.get("generation_stage_admission_total")
                .tag("orchestration_mode", "heavy")
                .tag("stage", "repair_build")
                .tag("outcome", "skipped")
                .counter()
                .count());
        assertTrue(session.asFlux()
                .any(event -> "agent_event".equals(event.getType())
                        && "DeadlinePolicy".equals(event.getData().get("agent")))
                .block(Duration.ofSeconds(1)));
    }

    @Test
    void mandatoryBuildIsRejectedBeforeStartingWhenDownstreamValidationCannotFit() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationStageAdmissionService service = service(meterRegistry);
        GenerationExecutionContextTest.MutableClock clock =
                new GenerationExecutionContextTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GenerationPreparation preparation = preparation("build-deadline");
        GenerationSession session = new GenerationSession(
                preparation,
                context("build-deadline", Duration.ofSeconds(69), clock)
        );

        GenerationDeadlineExceededException exception = assertThrows(
                GenerationDeadlineExceededException.class,
                () -> service.requireBuild(session, preparation, "create")
        );

        assertTrue(exception.getMessage().contains("stage=build"));
        assertEquals(0, session.executionContext().used(GenerationBudgetKind.BUILD_EXECUTION));
    }

    @Test
    void unmanagedLegacySessionRemainsCompatible() {
        GenerationStageAdmissionService service = service(new SimpleMeterRegistry());
        GenerationPreparation preparation = preparation("legacy");
        GenerationSession session = new GenerationSession(preparation);

        assertTrue(service.canRepair(session, preparation));
        service.requireBuild(session, preparation, "legacy");
        service.requireRuntimeValidation(session, preparation, "legacy");
    }

    private GenerationStageAdmissionService service(SimpleMeterRegistry meterRegistry) {
        return new GenerationStageAdmissionService(
                new GenerationStageAdmissionProperties(),
                new GenerationOrchestrationMetricsCollector(meterRegistry),
                new GenerationPerformanceMonitorService()
        );
    }

    private GenerationExecutionContext context(String taskId,
                                               Duration timeout,
                                               GenerationExecutionContextTest.MutableClock clock) {
        Duration modelTimeout = timeout.compareTo(Duration.ofSeconds(30)) < 0
                ? timeout
                : Duration.ofSeconds(30);
        return context(taskId, timeout, modelTimeout, clock);
    }

    private GenerationExecutionContext context(String taskId,
                                               Duration timeout,
                                               Duration modelTimeout,
                                               GenerationExecutionContextTest.MutableClock clock) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        return new GenerationExecutionContext(
                taskId,
                11L,
                22L,
                clock.instant(),
                new GenerationExecutionLimits(
                        timeout,
                        modelTimeout,
                        Duration.ofMillis(500),
                        budgets
                ),
                clock
        );
    }

    private GenerationPreparation preparation(String taskId) {
        return new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "create",
                "build a page",
                List.of(),
                new java.util.HashMap<>(),
                null,
                Map.of(),
                taskId
        );
    }
}
