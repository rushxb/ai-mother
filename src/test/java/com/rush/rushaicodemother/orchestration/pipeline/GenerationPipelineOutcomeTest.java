package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationPipelineOutcomeTest {

    @Test
    void completedOutcomeMustCarryResultSummary() {
        assertThrows(IllegalArgumentException.class, () -> GenerationPipelineOutcome.completed(
                "create", GenerationTaskStatus.SUCCESS, null, " "));
    }

    @Test
    void nonSuccessOutcomeMustCarryTerminalReason() {
        assertThrows(IllegalArgumentException.class, () -> GenerationPipelineOutcome.completed(
                "agent_edit", GenerationTaskStatus.FAILED, null, "任务状态：失败"));
    }

    @Test
    void outcomeMustNormalizeExternalTextAtTheBoundary() {
        GenerationPipelineOutcome outcome = GenerationPipelineOutcome.completed(
                " create ",
                GenerationTaskStatus.FAILED,
                " create_validation_failed ",
                " 任务状态：失败 "
        );

        assertEquals("create", outcome.route());
        assertEquals("create_validation_failed", outcome.reason());
        assertEquals("任务状态：失败", outcome.resultSummary());
    }
}
