package com.rush.rushaicodemother.orchestration.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.AiToolLoopGuardProperties;
import com.rush.rushaicodemother.core.error.GenerationAgentLoopException;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GenerationToolLoopGuardTest {

    private AiToolLoopGuardProperties properties;
    private GenerationOrchestrationMetricsCollector metricsCollector;

    @BeforeEach
    void setUp() {
        properties = new AiToolLoopGuardProperties();
        metricsCollector = mock(GenerationOrchestrationMetricsCollector.class);
    }

    @Test
    void canonicalJsonArgumentsMustDetectAnIdenticalCall() {
        GenerationToolLoopGuard guard = guard();
        ToolExecutionRequest first = request("call-1", "readFile",
                "{\"path\":\"src/App.vue\",\"offset\":0}");
        ToolExecutionRequest second = request("call-2", "readFile",
                "{ \"offset\" : 0, \"path\" : \"src/App.vue\" }");
        ToolExecutionRequest third = request("call-3", "readFile",
                "{\"offset\":0,\"path\":\"src/App.vue\"}");

        complete(guard, "task-json", first, "same-content");
        complete(guard, "task-json", second, "same-content");

        GenerationAgentLoopException failure = assertThrows(
                GenerationAgentLoopException.class,
                () -> guard.beforeInvocation("task-json", third));

        assertEquals(GenerationToolLoopGuard.REASON_IDENTICAL_CALL, failure.reasonCode());
        verify(metricsCollector).recordToolLoopGuard(
                GenerationToolLoopGuard.REASON_IDENTICAL_CALL, "readFile");
    }

    @Test
    void duplicateInvocationInTheSameToolRoundMustFailBeforeExecution() {
        GenerationToolLoopGuard guard = guard();
        ToolExecutionRequest first = request("call-1", "writeFile", "{\"path\":\"a.txt\"}");
        ToolExecutionRequest duplicate = request("call-2", "writeFile", "{\"path\":\"a.txt\"}");

        guard.beforeInvocation("task-concurrent", first);

        GenerationAgentLoopException failure = assertThrows(
                GenerationAgentLoopException.class,
                () -> guard.beforeInvocation("task-concurrent", duplicate));

        assertEquals(GenerationToolLoopGuard.REASON_IN_FLIGHT_DUPLICATE, failure.reasonCode());
    }

    @Test
    void alternatingRepeatedObservationsMustTripTheNoProgressLimit() {
        properties.setMaxIdenticalCalls(8);
        properties.setMaxNoProgressCalls(3);
        GenerationToolLoopGuard guard = guard();
        ToolExecutionRequest a1 = request("a-1", "readFile", "{\"path\":\"a.txt\"}");
        ToolExecutionRequest b1 = request("b-1", "readFile", "{\"path\":\"b.txt\"}");
        ToolExecutionRequest a2 = request("a-2", "readFile", "{\"path\":\"a.txt\"}");
        ToolExecutionRequest b2 = request("b-2", "readFile", "{\"path\":\"b.txt\"}");
        ToolExecutionRequest a3 = request("a-3", "readFile", "{\"path\":\"a.txt\"}");

        complete(guard, "task-cycle", a1, "A");
        complete(guard, "task-cycle", b1, "B");
        complete(guard, "task-cycle", a2, "A");
        complete(guard, "task-cycle", b2, "B");
        complete(guard, "task-cycle", a3, "A");

        GenerationAgentLoopException failure = assertThrows(
                GenerationAgentLoopException.class,
                () -> guard.beforeInvocation("task-cycle",
                        request("b-3", "readFile", "{\"path\":\"b.txt\"}")));

        assertEquals(GenerationToolLoopGuard.REASON_NO_PROGRESS, failure.reasonCode());
    }

    @Test
    void aNewObservationMustResetTheNoProgressCounter() {
        properties.setMaxIdenticalCalls(8);
        properties.setMaxNoProgressCalls(2);
        GenerationToolLoopGuard guard = guard();

        complete(guard, "task-progress", request("a-1", "readFile", "{\"path\":\"a\"}"), "A");
        complete(guard, "task-progress", request("b-1", "readFile", "{\"path\":\"b\"}"), "B");
        complete(guard, "task-progress", request("a-2", "readFile", "{\"path\":\"a\"}"), "A");
        complete(guard, "task-progress", request("c-1", "readFile", "{\"path\":\"c\"}"), "C");

        assertDoesNotThrow(() -> guard.beforeInvocation(
                "task-progress", request("b-2", "readFile", "{\"path\":\"b\"}")));
    }

    @Test
    void durableConversationMustRestoreTheRecentLoopWindow() {
        GenerationToolLoopGuard guard = guard();
        ToolExecutionRequest first = request("call-1", "readFile", "{\"path\":\"a.txt\"}");
        ToolExecutionRequest second = request("call-2", "readFile", "{\"path\":\"a.txt\"}");
        List<ChatMessage> transcript = List.of(
                AiMessage.builder().toolExecutionRequests(List.of(first)).build(),
                ToolExecutionResultMessage.from(first, "same"),
                AiMessage.builder().toolExecutionRequests(List.of(second)).build(),
                ToolExecutionResultMessage.from(second, "same")
        );

        guard.restore("task-restored", transcript);

        GenerationAgentLoopException failure = assertThrows(
                GenerationAgentLoopException.class,
                () -> guard.beforeInvocation("task-restored",
                        request("call-3", "readFile", "{\"path\":\"a.txt\"}")));
        assertEquals(GenerationToolLoopGuard.REASON_IDENTICAL_CALL, failure.reasonCode());
    }

    private GenerationToolLoopGuard guard() {
        return new GenerationToolLoopGuard(properties, new ObjectMapper(), metricsCollector);
    }

    private void complete(GenerationToolLoopGuard guard,
                          String taskId,
                          ToolExecutionRequest request,
                          String result) {
        guard.beforeInvocation(taskId, request);
        guard.completeInvocation(taskId, request, result, false);
    }

    private ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments(arguments)
                .build();
    }
}
