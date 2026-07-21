package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationStreamingModelCallSupervisor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class AiToolContinuationEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private final GenerationStreamingModelCallSupervisor modelCallSupervisor =
            new GenerationStreamingModelCallSupervisor();

    @AfterEach
    void tearDown() {
        modelCallSupervisor.close();
    }

    @Test
    void approvedInvocationMustRecoverFromDurableTranscriptWhenExternalMemoryIsEmpty() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{\"action\":\"rollbackSnapshot\"}")
                .build();
        List<ChatMessage> transcript = List.of(
                UserMessage.from("rollback to safe"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        );
        CountingSnapshotTool tool = new CountingSnapshotTool();
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{tool});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, new CompletingStreamingModel());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        ToolApprovalRecord approved = approval(ToolApprovalStatus.APPROVED, checkpoint);
        ToolApprovalRecord executing = executionApproval(
                ToolApprovalStatus.EXECUTING, checkpoint, null);
        ToolApprovalRecord consumed = executionApproval(
                ToolApprovalStatus.CONSUMED, checkpoint,
                new ToolExecutionOutcome(false, "rollbackSnapshot:11"));
        ToolApprovalService approvalService = mock(ToolApprovalService.class);
        when(approvalService.beginExecution(approved)).thenReturn(executing);
        when(approvalService.completeExecution(any(), any())).thenReturn(consumed);
        AiToolContinuationEngine engine = new AiToolContinuationEngine(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                approvalService, new GenerationToolExecutionContextService(),
                mock(AiToolInvocationPolicy.class), modelCallSupervisor);
        GenerationExecutionContext executionContext = executionContext();

        List<GenerationStreamEvent> events = engine.continueAfterDecision(
                        approved,
                        state(executionContext, checkpoint, transcript),
                        executionContext,
                        () -> false,
                        ignored -> { })
                .collectList()
                .block();

        assertEquals(1, tool.executions.get());
        assertEquals(3, memoryStore.getMessages(11L).stream()
                .filter(message -> message instanceof UserMessage
                        || message instanceof AiMessage
                        || message instanceof ToolExecutionResultMessage)
                .filter(message -> !(message instanceof UserMessage)).count());
        assertEquals(1, memoryStore.getMessages(11L).stream()
                .filter(UserMessage.class::isInstance).count());
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.TOOL_RESULT.equals(event.getType())));
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())));
        assertEquals(1, executionContext.used(GenerationBudgetKind.MODEL_ATTEMPT));
    }

    @Test
    void rejectedInvocationMustBecomeToolErrorWithoutExecutingSideEffect() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{\"action\":\"rollbackSnapshot\"}")
                .build();
        memoryStore.updateMessages(11L, List.of(
                UserMessage.from("rollback to safe"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        ));
        CountingSnapshotTool tool = new CountingSnapshotTool();
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{tool});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, new CompletingStreamingModel());
        AiToolContinuationEngine engine = new AiToolContinuationEngine(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                mock(AiToolInvocationPolicy.class), modelCallSupervisor);
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);

        GenerationExecutionContext executionContext = executionContext();
        engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        state(executionContext, checkpoint, memoryStore.getMessages(11L)), executionContext,
                        () -> false, ignored -> { })
                .blockLast();

        assertEquals(0, tool.executions.get());
        ToolExecutionResultMessage result = memoryStore.getMessages(11L).stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.TRUE, result.isError());
    }

    @Test
    void consumedInvocationMustReplayDurableResultWithoutRepeatingSideEffect() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{\"action\":\"rollbackSnapshot\"}")
                .build();
        memoryStore.updateMessages(11L, List.of(
                UserMessage.from("rollback to safe"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        ));
        CountingSnapshotTool tool = new CountingSnapshotTool();
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{tool});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, new CompletingStreamingModel());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        ToolApprovalRecord consumed = executionApproval(
                ToolApprovalStatus.CONSUMED, checkpoint,
                new ToolExecutionOutcome(false, "already completed"));
        ToolApprovalService approvalService = mock(ToolApprovalService.class);
        when(approvalService.beginExecution(consumed)).thenReturn(consumed);
        AiToolContinuationEngine engine = new AiToolContinuationEngine(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                approvalService, new GenerationToolExecutionContextService(),
                mock(AiToolInvocationPolicy.class), modelCallSupervisor);
        GenerationExecutionContext executionContext = executionContext();

        engine.continueAfterDecision(
                        consumed, state(executionContext, checkpoint, memoryStore.getMessages(11L)), executionContext,
                        () -> false, ignored -> { })
                .blockLast();

        assertEquals(0, tool.executions.get());
        ToolExecutionResultMessage result = memoryStore.getMessages(11L).stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("already completed", result.text());
    }

    @Test
    void everyContinuedModelTurnMustConsumeTheTaskWideAttemptBudget() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{\"action\":\"rollbackSnapshot\"}")
                .build();
        memoryStore.updateMessages(11L, List.of(
                UserMessage.from("rollback and continue"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        ));
        CountingSnapshotTool tool = new CountingSnapshotTool();
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{tool});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        TwoTurnStreamingModel model = new TwoTurnStreamingModel();
        stubExecutionModel(modelFactory, model);
        AiToolContinuationEngine engine = new AiToolContinuationEngine(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                mock(AiToolInvocationPolicy.class), modelCallSupervisor);
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        GenerationExecutionContext executionContext = executionContext();

        engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        state(executionContext, checkpoint, memoryStore.getMessages(11L)), executionContext,
                        () -> false, ignored -> { })
                .blockLast();

        assertEquals(2, model.calls.get());
        assertEquals(1, tool.executions.get());
        assertEquals(2, executionContext.used(GenerationBudgetKind.MODEL_ATTEMPT));
    }

    @Test
    void legacyCheckpointMustFailClosedWhenExternalMemoryIsMissing() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{}")
                .build();
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{new CountingSnapshotTool()});
        AiToolContinuationEngine engine = new AiToolContinuationEngine(
                memoryStore, toolManager, mock(StreamingModelFactory.class),
                mock(ToolExecutionFailurePolicy.class), mock(ToolApprovalService.class),
                new GenerationToolExecutionContextService(), mock(AiToolInvocationPolicy.class),
                modelCallSupervisor);
        GenerationExecutionContext executionContext = executionContext();

        assertThrows(IllegalStateException.class, () -> engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        legacyState(executionContext), executionContext,
                        () -> false, ignored -> { })
                .blockLast());
    }

    private ToolApprovalRecord approval(ToolApprovalStatus status,
                                        ToolInvocationCheckpoint checkpoint) {
        return new ToolApprovalRecord(
                "a".repeat(64), "task-1", 11L, 7L,
                DestructiveToolAction.SNAPSHOT_ROLLBACK, "{}", status,
                NOW.minusSeconds(10), NOW.plusSeconds(600),
                7L, NOW, null, 2L, checkpoint);
    }

    private void stubExecutionModel(StreamingModelFactory modelFactory,
                                    StreamingChatModel delegate) {
        when(modelFactory.createExecutionModel(any(), any(), any())).thenAnswer(invocation -> {
            Runnable beforeProviderAttempt = invocation.getArgument(2);
            return new StreamingChatModel() {
                @Override
                public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    beforeProviderAttempt.run();
                    delegate.doChat(request, handler);
                }
            };
        });
    }

    private GenerationExecutionContext executionContext() {
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-1", 11L, 7L, NOW.minusSeconds(30),
                runtimeProperties.toLimits(), Clock.fixed(NOW, ZoneOffset.UTC));
        context.bindExecutionFence(new GenerationExecutionFence("task-1", "test-worker", 1L));
        return context;
    }

    private GenerationToolContinuationState state(GenerationExecutionContext context,
                                                  ToolInvocationCheckpoint checkpoint,
                                                  List<ChatMessage> transcript) {
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                false, "create", "build a dashboard", List.of(),
                new java.util.LinkedHashMap<>(), null, Map.of(), "task-1");
        DurableToolConversation durableConversation = new DurableToolConversationCodec().capture(
                transcript,
                checkpoint.requestId(),
                checkpoint.toolName(),
                checkpoint.argumentsJson()
        );
        return new GenerationToolContinuationState(
                GenerationToolContinuationState.CURRENT_SCHEMA_VERSION,
                "task-1", 11L, 7L, "heavy_generation", "rollback to safe",
                CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced(),
                preparation, context.limits(), context.snapshot(), durableConversation, NOW);
    }

    private GenerationToolContinuationState legacyState(GenerationExecutionContext context) {
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                false, "create", "build a dashboard", List.of(),
                new java.util.LinkedHashMap<>(), null, Map.of(), "task-1");
        return new GenerationToolContinuationState(
                2,
                "task-1", 11L, 7L, "heavy_generation", "rollback to safe",
                CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced(),
                preparation, context.limits(), context.snapshot(), NOW);
    }

    private ToolApprovalRecord executionApproval(
            ToolApprovalStatus status,
            ToolInvocationCheckpoint checkpoint,
            ToolExecutionOutcome outcome
    ) {
        return new ToolApprovalRecord(
                "a".repeat(64), "task-1", 11L, 7L,
                DestructiveToolAction.SNAPSHOT_ROLLBACK, "{}", status,
                NOW.minusSeconds(10), NOW.plusSeconds(600), 7L, NOW,
                status == ToolApprovalStatus.CONSUMED ? NOW : null,
                3L, checkpoint, NOW, outcome, 1);
    }

    private static final class CompletingStreamingModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("continued"))
                    .build());
        }
    }

    private static final class TwoTurnStreamingModel implements StreamingChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            if (calls.incrementAndGet() == 1) {
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.builder()
                                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                        .id("call-2")
                                        .name("manageSnapshot")
                                        .arguments("{\"action\":\"listSnapshots\"}")
                                        .build()))
                                .build())
                        .build());
                return;
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("continued after tool"))
                    .build());
        }
    }

    private static final class CountingSnapshotTool extends BaseTool {
        private final AtomicInteger executions = new AtomicInteger();

        @Tool
        public String manageSnapshot(@P("action") String action, @ToolMemoryId Long appId) {
            executions.incrementAndGet();
            return action + ":" + appId;
        }

        @Override
        public ToolRiskLevel getRiskLevel() {
            return ToolRiskLevel.DESTRUCTIVE;
        }

        @Override
        public String getToolName() {
            return "manageSnapshot";
        }

        @Override
        public String getDisplayName() {
            return "snapshot";
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            return "snapshot";
        }
    }

    private static final class InMemoryChatMemoryStore implements ChatMemoryStore {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public synchronized List<ChatMessage> getMessages(Object memoryId) {
            return List.copyOf(messages);
        }

        @Override
        public synchronized void updateMessages(Object memoryId, List<ChatMessage> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
        }

        @Override
        public synchronized void deleteMessages(Object memoryId) {
            messages.clear();
        }
    }
}
