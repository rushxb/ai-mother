package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationFinalizationCommandCodecTest {

    @Test
    void roundTripMustPreserveRecoveryEvidence() {
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-1", 11L,
                new GenerationExecutionFence("task-1", "worker-a", 7L),
                GenerationTaskStatus.SUCCESS, null, "完成摘要",
                new GenerationOutcomeQuality(
                        "high", 4, true, 0, 1200L, null,
                        LocalDateTime.parse("2026-08-12T10:00:00"), null));

        assertEquals(command, GenerationFinalizationCommandCodec.fromJson(
                GenerationFinalizationCommandCodec.toJson(command)));
    }
}
