package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceiptFactory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationFinalizationCommandCodecTest {

    @Test
    void roundTripMustPreserveRecoveryEvidence() {
        GenerationDeliveryReceipt receipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "agent_edit", GenerationTaskStatus.SUCCESS,
                GenerationCompletionEvidenceSet.empty(),
                GenerationOutcomeQuality.ofSuccess(4, 0, true, 1_200L));
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-1", 11L,
                new GenerationExecutionFence("task-1", "worker-a", 7L),
                GenerationTaskStatus.SUCCESS, null, "完成摘要",
                new GenerationOutcomeQuality(
                        "high", 4, true, 0, 1200L, null,
                        LocalDateTime.parse("2026-08-12T10:00:00"), null),
                receipt);

        assertEquals(command, GenerationFinalizationCommandCodec.fromJson(
                GenerationFinalizationCommandCodec.toJson(command)));
    }

    @Test
    void codecMustReadVersionOnePayloadWithoutDeliveryReceipt() {
        String versionOneJson = """
                {
                  "taskId":"task-v1",
                  "appId":11,
                  "executionFence":{"taskId":"task-v1","leaseOwner":"worker-a","executionEpoch":2},
                  "status":"SUCCESS",
                  "reason":null,
                  "memorySummary":"旧终态",
                  "outcomeQuality":null
                }
                """;

        GenerationFinalizationCommand restored = GenerationFinalizationCommandCodec.fromJson(versionOneJson);

        assertEquals("task-v1", restored.taskId());
        assertEquals(null, restored.deliveryReceipt());
        org.junit.jupiter.api.Assertions.assertTrue(
                GenerationFinalizationCommandCodec.supportsSchemaVersion(1));
        org.junit.jupiter.api.Assertions.assertTrue(
                GenerationFinalizationCommandCodec.supportsSchemaVersion(
                        GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION));
    }
}
