package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationTerminalOutcomeTest {

    @Test
    void watchdogDeadlineCancellationMustRemainADeadlineTerminalOutcome() {
        GenerationExecutionContext context = context("task-deadline-outcome");
        GenerationSession session = new GenerationSession(null, context);

        session.cancel("deadline_exceeded");

        assertEquals(GenerationTerminalOutcome.DEADLINE_EXCEEDED,
                GenerationTerminalOutcome.resolve(session, null));
    }

    @Test
    void explicitUserCancellationMustNotBeReclassifiedAsADeadline() {
        GenerationExecutionContext context = context("task-user-cancel-outcome");
        GenerationSession session = new GenerationSession(null, context);

        session.cancel("user_requested");

        assertEquals(GenerationTerminalOutcome.CANCELLED,
                GenerationTerminalOutcome.resolve(session, null));
    }

    private GenerationExecutionContext context(String taskId) {
        return new GenerationExecutionContextService(new GenerationRuntimeProperties())
                .start(taskId, 1L, 2L);
    }
}
