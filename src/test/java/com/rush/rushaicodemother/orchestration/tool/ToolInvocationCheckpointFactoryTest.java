package com.rush.rushaicodemother.orchestration.tool;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolInvocationCheckpointFactoryTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void checkpointMustRoundTripExactPreparationProfileAndBudgetState() {
        GenerationSessionRegistry registry = new GenerationSessionRegistry(
                new GenerationSessionProperties());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        GenerationExecutionContext executionContext = new GenerationExecutionContext(
                "task-1", 11L, 7L, NOW.minusSeconds(30),
                new GenerationRuntimeProperties().toLimits(), clock);
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "create",
                "build a dashboard",
                List.of(),
                new LinkedHashMap<>(),
                null,
                Map.of("planner", 12L),
                "task-1"
        );
        GenerationSession session = new GenerationSession(preparation, executionContext);
        session.bindTaskRequest(new GenerationTaskRequest(
                App.builder().id(11L).userId(7L).build(),
                "build a dashboard",
                User.builder().id(7L).build()));
        session.recordRoute("heavy_generation");
        registry.put(11L, session);
        GenerationTraceContext traceContext = new GenerationTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null);
        GenerationTraceContextBridge traceContextBridge = mock(GenerationTraceContextBridge.class);
        when(traceContextBridge.capture()).thenReturn(traceContext);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{\"action\":\"rollbackSnapshot\"}")
                .build();
        List<ChatMessage> transcript = List.of(
                UserMessage.from("build a dashboard"),
                AiMessage.builder()
                        .thinking("A rollback requires the project owner's approval.")
                        .toolExecutionRequests(List.of(request))
                        .build()
        );
        ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);
        when(chatMemoryStore.getMessages(11L)).thenReturn(transcript);
        DurableToolConversationCodec conversationCodec = new DurableToolConversationCodec();
        ToolInvocationCheckpointFactory factory = new ToolInvocationCheckpointFactory(
                registry,
                JsonMapper.builder().findAndAddModules().build(),
                traceContextBridge,
                chatMemoryStore,
                conversationCodec,
                clock
        );
        GenerationPerformanceProfile profile = GenerationPerformanceProfile.qualityFirst();
        executionContext.beginAgentAttempt(profile.maxToolInvocations());
        executionContext.reserveAgentModelTurn(profile.maxToolInvocations());

        ToolInvocationCheckpoint checkpoint = factory.capture(
                "task-1", request.id(), request.name(), request.arguments(),
                CodeGenTypeEnum.VUE_PROJECT, profile);
        GenerationToolContinuationState restored = factory.restore(checkpoint);

        assertEquals("task-1", restored.taskId());
        assertEquals("heavy_generation", restored.route());
        assertEquals(profile, restored.performanceProfile());
        assertEquals(preparation, restored.preparation());
        assertEquals(executionContext.snapshot(), restored.execution());
        assertEquals(1L, restored.execution().agentAttemptEpoch());
        assertEquals(profile.maxToolInvocations(),
                restored.execution().agentToolRoundLimit());
        assertEquals(1, restored.execution().agentModelTurnsStarted());
        assertEquals(traceContext, restored.traceContext());
        assertEquals(transcript, conversationCodec.restore(
                restored.durableConversation(), checkpoint));
        assertEquals(NOW, restored.capturedAt());
    }
}
