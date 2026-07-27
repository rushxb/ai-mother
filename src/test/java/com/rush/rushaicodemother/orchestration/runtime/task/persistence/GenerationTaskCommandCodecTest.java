package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenerationTaskCommandCodecTest {

    @Test
    void roundTripPreservesStructuredRoutingReasonAndSlaEnvelope() {
        Instant submittedAt = Instant.parse("2026-07-17T00:00:00Z");
        GenerationSlaEnvelope envelope = envelope();
        GenerationTraceContext traceContext = new GenerationTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "vendor=value"
        );
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-codec", 1L, 2L, 100L, "build app", CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.CREATE, 0.95, "template first", FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD, "",
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST, envelope, traceContext,
                submittedAt, envelope.totalDeadline(submittedAt));

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(
                GenerationTaskCommandCodec.toJson(command));

        assertEquals(GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST, restored.routingDecisionCode());
        assertEquals("create-test", restored.slaEnvelope().profile());
        assertEquals(Duration.ofSeconds(45),
                restored.slaEnvelope().firstPreviewCompletionReserve());
        assertEquals(2, restored.slaEnvelope().toLimits().limit(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(100L, restored.tenantId());
        assertEquals(traceContext, restored.traceContext());
    }

    @Test
    void oldCommandJsonWithoutNewFieldsRemainsReadable() {
        String json = """
                {"schemaVersion":1,"taskId":"legacy-task","appId":1,"userId":2,
                "userPrompt":"legacy","codeGenType":"VUE_PROJECT","mode":"AGENT_EDIT",
                "routingConfidence":0.7,"routingReason":"legacy","fallbackPolicy":"NONE",
                "expectedValidationLevel":"BUILD","fallbackReason":"",
                "submittedAt":"2026-07-17T00:00:00Z","deadlineAt":"2026-07-17T00:10:00Z"}
                """;

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertEquals(GenerationRoutingDecisionCode.UNKNOWN, restored.routingDecisionCode());
        assertNull(restored.slaEnvelope());
        assertNull(restored.tenantId());
        assertEquals(GenerationTraceContext.empty(), restored.traceContext());
    }

    @Test
    void legacyModelAttemptBudgetMustMapToSeparatedRuntimeBudgets() {
        String json = """
                {"schemaVersion":3,"taskId":"legacy-budget-task","appId":1,"userId":2,
                "userPrompt":"legacy","codeGenType":"VUE_PROJECT","mode":"AGENT_EDIT",
                "routingConfidence":0.7,"routingReason":"legacy","fallbackPolicy":"NONE",
                "expectedValidationLevel":"BUILD","fallbackReason":"",
                "slaEnvelope":{"profile":"legacy-budget","firstPreviewTimeout":"PT1M",
                "totalTimeout":"PT10M","modelCallTimeout":"PT2M",
                "minimumOperationTimeout":"PT0.5S","budgets":{"MODEL_ATTEMPT":2,
                "TOOL_WRITE":10,"BUILD_EXECUTION":1,"REPAIR_ROUND":1},"reason":"legacy"},
                "submittedAt":"2026-07-17T00:00:00Z","deadlineAt":"2026-07-17T00:10:00Z"}
                """;

        GenerationTaskCommand restored = GenerationTaskCommandCodec.fromJson(json);

        assertEquals(2, restored.slaEnvelope().toLimits()
                .limit(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(16, restored.slaEnvelope().toLimits()
                .limit(GenerationBudgetKind.MODEL_TURN));
        assertEquals(4, restored.slaEnvelope().toLimits()
                .limit(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
        assertEquals(Duration.ofMillis(500),
                restored.slaEnvelope().firstPreviewCompletionReserve());
    }

    private GenerationSlaEnvelope envelope() {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        return new GenerationSlaEnvelope(
                "create-test", Duration.ofMinutes(1), Duration.ofSeconds(45), Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofMillis(500), Map.copyOf(budgets), "test");
    }
}
