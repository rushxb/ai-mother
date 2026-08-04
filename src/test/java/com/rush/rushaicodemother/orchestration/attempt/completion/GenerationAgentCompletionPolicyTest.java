package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentTurnPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationAgentCompletionPolicyTest {

    private final GenerationAgentCompletionPolicy policy = new GenerationAgentCompletionPolicy();

    @Test
    void workspaceMutationMustAllowCompletion() {
        GenerationExecutionContext context = executionContext();
        context.recordSuccessfulWorkspaceMutations(1);

        GenerationAgentCompletionPolicy.AgentCompletionDecision decision =
                policy.evaluate(context, preparedTurn(true));

        assertTrue(decision.completable());
        assertFalse(decision.retryable());
    }

    @Test
    void missingMutationWithToolsAvailableMustRequestCorrectionRound() {
        GenerationAgentCompletionPolicy.AgentCompletionDecision decision =
                policy.evaluate(executionContext(), preparedTurn(true));

        assertFalse(decision.completable());
        assertTrue(decision.retryable());
        assertTrue(decision.message().contains("继续使用写工具"));
    }

    @Test
    void missingMutationInFinalTurnMustFailClosed() {
        GenerationAgentCompletionPolicy.AgentCompletionDecision decision =
                policy.evaluate(executionContext(), preparedTurn(false));

        assertFalse(decision.completable());
        assertFalse(decision.retryable());
        assertTrue(decision.toException().getMessage().contains("未产生有效工作区变更"));
    }

    private GenerationAgentTurnPolicy.PreparedModelTurn preparedTurn(boolean toolCallsAllowed) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("测试任务")))
                .build();
        return new GenerationAgentTurnPolicy.PreparedModelTurn(
                request, 1, 2, toolCallsAllowed, true);
    }

    private GenerationExecutionContext executionContext() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        return new GenerationExecutionContext(
                "agent-completion-test", 1L, 2L, now,
                new GenerationRuntimeProperties().toLimits(), Clock.fixed(now, ZoneOffset.UTC));
    }
}
