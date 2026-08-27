package com.rush.rushaicodemother.orchestration.runtime.agent;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ExitTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.context.AgentConversationFolder;
import com.rush.rushaicodemother.orchestration.context.AgentConversationTokenAccountant;
import com.rush.rushaicodemother.orchestration.context.AgentConversationWindowPolicy;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.context.OpenAiCompatibleContextTokenEstimator;
import com.rush.rushaicodemother.orchestration.context.ToolRoundPathExtractor;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationStreamingModelCallSupervisor;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.orchestration.tool.AiToolInvocationPolicy;
import com.rush.rushaicodemother.orchestration.tool.CompletedToolCallContextCompactor;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.DurableToolConversation;
import com.rush.rushaicodemother.orchestration.tool.DurableToolConversationCodec;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolContinuationState;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.ToolBatchExecutionPlanner;
import com.rush.rushaicodemother.orchestration.tool.ToolBatchExecutor;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalStatus;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionFailurePolicy;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionOutcome;
import com.rush.rushaicodemother.orchestration.tool.ToolInvocationCheckpoint;
import com.rush.rushaicodemother.orchestration.tool.ToolInvocationCheckpointFactory;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class DefaultGenerationAgentRuntimeTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private final GenerationStreamingModelCallSupervisor modelCallSupervisor =
            new GenerationStreamingModelCallSupervisor();

    @AfterEach
    void tearDown() {
        modelCallSupervisor.close();
    }

    @Test
    void initialGenerationMustUseTheSameExplicitModelToolLoop() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        CountingSnapshotTool tool = new CountingSnapshotTool();
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{tool});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        TwoTurnStreamingModel model = new TwoTurnStreamingModel();
        stubExecutionModel(modelFactory, model);
        GenerationAgentConversationInitializer initializer =
                mock(GenerationAgentConversationInitializer.class);
        ChatMemory initialMemory = MessageWindowChatMemory.withMaxMessages(8);
        initialMemory.add(UserMessage.from("生成管理后台"));
        when(initializer.initialize(any(), any())).thenReturn(initialMemory);
        CompletedToolCallContextCompactor compactor =
                mock(CompletedToolCallContextCompactor.class);
        when(compactor.compact(any(ChatRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore,
                toolManager,
                modelFactory,
                mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class),
                new GenerationToolExecutionContextService(),
                new GenerationPerformanceMonitorService(),
                passthroughPolicy(),
                new DurableToolConversationCodec(),
                modelCallSupervisor,
                compactor,
                stageAdmissionService(),
                new GenerationAgentTurnPolicy(),
                initializer,
                new ToolBatchExecutionPlanner(toolManager),
                new ToolBatchExecutor(),
                windowPolicy()
        );
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);
        GenerationAgentExecutionRequest request = new GenerationAgentExecutionRequest(
                11L,
                "生成管理后台",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationPerformanceProfile.balanced(),
                "D:\\generated\\vue_project_11",
                executionContext,
                () -> false,
                ignored -> { }
        );

        List<GenerationStreamEvent> events = engine.start(request)
                .collectList()
                .block();

        assertEquals(1, tool.executions.get());
        assertEquals(2, model.calls.get());
        assertEquals(2, executionContext.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(0, executionContext.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(2, executionContext.agentModelTurnsStarted());
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.TOOL_CALL.equals(event.getType())));
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.TOOL_RESULT.equals(event.getType())));
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())
                        && "D:\\generated\\vue_project_11".equals(
                        event.getData().get("projectPath"))));
    }

    @Test
    void privateThinkingMustBecomeStructuredProgressWithoutLeakingContent() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[0]);
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, new ThinkingStreamingModel());
        GenerationAgentConversationInitializer initializer =
                mock(GenerationAgentConversationInitializer.class);
        ChatMemory initialMemory = MessageWindowChatMemory.withMaxMessages(8);
        initialMemory.add(UserMessage.from("生成管理后台"));
        when(initializer.initialize(any(), any())).thenReturn(initialMemory);
        CompletedToolCallContextCompactor compactor =
                mock(CompletedToolCallContextCompactor.class);
        when(compactor.compact(any(ChatRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DefaultGenerationAgentRuntime runtime = new DefaultGenerationAgentRuntime(
                memoryStore,
                toolManager,
                modelFactory,
                mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class),
                new GenerationToolExecutionContextService(),
                new GenerationPerformanceMonitorService(),
                passthroughPolicy(),
                new DurableToolConversationCodec(),
                modelCallSupervisor,
                compactor,
                stageAdmissionService(),
                new GenerationAgentTurnPolicy(),
                initializer,
                new ToolBatchExecutionPlanner(toolManager),
                new ToolBatchExecutor(),
                windowPolicy()
        );
        GenerationAgentExecutionRequest request = new GenerationAgentExecutionRequest(
                11L,
                "生成管理后台",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationPerformanceProfile.balanced(),
                "D:\\generated\\vue_project_11",
                executionContextWithMutation(),
                () -> false,
                ignored -> { }
        );

        List<GenerationStreamEvent> events = runtime.start(request)
                .collectList()
                .block();

        assertTrue(events.stream().noneMatch(event ->
                GenerationStreamEvent.AI_THINKING_DELTA.equals(event.getType())));
        assertTrue(events.stream().noneMatch(event ->
                event.toString().contains("private chain of thought")));
        assertEquals(List.of("running", "done"), events.stream()
                .filter(event -> GenerationStreamEvent.AGENT_EVENT.equals(event.getType()))
                .map(event -> String.valueOf(event.getData().get("status")))
                .toList());
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.AI_DELTA.equals(event.getType())
                        && "visible answer".equals(event.getText())));
    }

    @Test
    void initialApprovalCheckpointMustContainPromptUserMessageAndExactAgentLedger() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        GenerationExecutionContext executionContext = executionContext();
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                false, "create", "生成管理后台", List.of(),
                new java.util.LinkedHashMap<>(), null, Map.of(), "task-1");
        GenerationSession session = new GenerationSession(preparation, executionContext);
        session.bindTaskRequest(new GenerationTaskRequest(
                App.builder().id(11L).userId(7L).build(),
                "生成管理后台",
                User.builder().id(7L).build()));
        session.recordRoute("heavy_generation");
        GenerationSessionRegistry sessionRegistry = new GenerationSessionRegistry(
                new GenerationSessionProperties());
        sessionRegistry.put(11L, session);
        DurableToolConversationCodec conversationCodec =
                new DurableToolConversationCodec();
        ToolInvocationCheckpointFactory checkpointFactory =
                new ToolInvocationCheckpointFactory(
                        sessionRegistry,
                        JsonMapper.builder().findAndAddModules().build(),
                        GenerationTraceContextBridge.NOOP,
                        memoryStore,
                        conversationCodec
                );
        ToolApprovalService approvalService = mock(ToolApprovalService.class);
        ToolExecutionFailurePolicy failurePolicy = new ToolExecutionFailurePolicy(
                approvalService, checkpointFactory);
        GenerationApprovalRequiredException approvalRequired =
                new GenerationApprovalRequiredException(
                        "task-1",
                        DestructiveToolAction.SNAPSHOT_ROLLBACK,
                        "a".repeat(64),
                        Map.of("action", "rollbackSnapshot"));
        GenerationPerformanceProfile profile = GenerationPerformanceProfile.balanced();
        AiToolInvocationPolicy invocationPolicy = mock(AiToolInvocationPolicy.class);
        when(invocationPolicy.governModelTurn(
                any(String.class), anyInt(), any(ChatRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        doThrow(approvalRequired).when(invocationPolicy)
                .authorize(any(), eq(CodeGenTypeEnum.VUE_PROJECT), eq(profile));
        ToolManager toolManager = mock(ToolManager.class);
        CountingSnapshotTool tool = new CountingSnapshotTool();
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{tool});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, new ApprovalStreamingModel());
        GenerationAgentConversationInitializer initializer =
                mock(GenerationAgentConversationInitializer.class);
        MessageWindowChatMemory initialMemory = MessageWindowChatMemory.builder()
                .id(11L)
                .chatMemoryStore(memoryStore)
                .maxMessages(8)
                .build();
        initialMemory.add(SystemMessage.from("当前工程系统提示"));
        initialMemory.add(UserMessage.from("生成管理后台"));
        when(initializer.initialize(any(), any())).thenReturn(initialMemory);
        CompletedToolCallContextCompactor compactor =
                mock(CompletedToolCallContextCompactor.class);
        when(compactor.compact(any(ChatRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DefaultGenerationAgentRuntime runtime = new DefaultGenerationAgentRuntime(
                memoryStore,
                toolManager,
                modelFactory,
                failurePolicy,
                approvalService,
                new GenerationToolExecutionContextService(),
                new GenerationPerformanceMonitorService(),
                invocationPolicy,
                conversationCodec,
                modelCallSupervisor,
                compactor,
                stageAdmissionService(),
                new GenerationAgentTurnPolicy(),
                initializer,
                new ToolBatchExecutionPlanner(toolManager),
                new ToolBatchExecutor(),
                windowPolicy()
        );
        GenerationAgentExecutionRequest request = new GenerationAgentExecutionRequest(
                11L,
                "生成管理后台",
                CodeGenTypeEnum.VUE_PROJECT,
                profile,
                "D:\\generated\\vue_project_11",
                executionContext,
                () -> false,
                ignored -> { }
        );

        assertThrows(GenerationApprovalRequiredException.class,
                () -> runtime.start(request).blockLast());

        ArgumentCaptor<ToolInvocationCheckpoint> checkpointCaptor =
                ArgumentCaptor.forClass(ToolInvocationCheckpoint.class);
        verify(approvalService).requestApproval(
                eq("task-1"),
                eq(DestructiveToolAction.SNAPSHOT_ROLLBACK),
                eq("a".repeat(64)),
                any(),
                checkpointCaptor.capture());
        ToolInvocationCheckpoint checkpoint = checkpointCaptor.getValue();
        GenerationToolContinuationState restored = checkpointFactory.restore(checkpoint);
        List<ChatMessage> restoredMessages = conversationCodec.restore(
                restored.durableConversation(), checkpoint);

        assertEquals(3, restoredMessages.size());
        assertEquals("当前工程系统提示",
                ((SystemMessage) restoredMessages.getFirst()).text());
        assertEquals("生成管理后台",
                ((UserMessage) restoredMessages.get(1)).singleText());
        assertEquals("call-approval",
                ((AiMessage) restoredMessages.getLast())
                        .toolExecutionRequests().getFirst().id());
        assertEquals(1L, restored.execution().agentAttemptEpoch());
        assertEquals(profile.maxToolInvocations(),
                restored.execution().agentToolRoundLimit());
        assertEquals(1, restored.execution().agentModelTurnsStarted());
        assertEquals(0, tool.executions.get());
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
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                approvalService, new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);

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
        assertEquals(1, executionContext.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(0, executionContext.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
    }

    @Test
    void approvedMutationEvidenceMustSurviveDurableOutcomeReplay() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-delete")
                .name("deleteFile")
                .arguments("{\"relativeFilePath\":\"src/obsolete.ts\"}")
                .build();
        List<ChatMessage> transcript = List.of(
                UserMessage.from("delete obsolete file"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        );
        CountingDeleteTool tool = new CountingDeleteTool();
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{tool});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, new CompletingStreamingModel());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        ToolApprovalRecord approved = approval(
                ToolApprovalStatus.APPROVED, checkpoint, DestructiveToolAction.FILE_DELETE);
        ToolApprovalRecord executing = executionApproval(
                ToolApprovalStatus.EXECUTING, checkpoint, null,
                DestructiveToolAction.FILE_DELETE);
        ToolApprovalService approvalService = mock(ToolApprovalService.class);
        when(approvalService.beginExecution(approved)).thenReturn(executing);
        when(approvalService.completeExecution(eq(executing), any()))
                .thenAnswer(invocation -> executionApproval(
                        ToolApprovalStatus.CONSUMED,
                        checkpoint,
                        invocation.getArgument(1),
                        DestructiveToolAction.FILE_DELETE
                ));
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                approvalService, new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);

        engine.continueAfterDecision(
                        approved,
                        state(executionContext, checkpoint, transcript),
                        executionContext,
                        () -> false,
                        ignored -> { })
                .blockLast();

        assertEquals(1, tool.executions.get());
        ToolExecutionResultMessage result = memoryStore.getMessages(11L).stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .filter(message -> pendingRequest.id().equals(message.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("src/obsolete.ts"),
                ToolResultEvidence.effectiveMutationPaths(result),
                "审批终态重放不能丢失真实删除路径证据");
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
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);

        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);
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
    void continuationMustReserveCompletionWindowInsteadOfStartingAnotherModelTurn() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{}")
                .build();
        memoryStore.updateMessages(11L, List.of(
                UserMessage.from("keep current project"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        ));
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{new CountingSnapshotTool()});
        StreamingChatModel delegate = mock(StreamingChatModel.class);
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, delegate);
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        GenerationExecutionContext executionContext = expiringExecutionContext();
        assumeWorkspaceMutation(executionContext);

        List<GenerationStreamEvent> events = engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        state(executionContext, checkpoint, memoryStore.getMessages(11L)),
                        executionContext,
                        () -> false,
                        ignored -> { })
                .collectList()
                .block();

        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.AGENT_EVENT.equals(event.getType())
                        && "reserved_completion".equals(event.getData().get("status"))));
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())
                        && "codegen_done".equals(event.getData().get("stage"))));
        assertEquals(0, executionContext.used(GenerationBudgetKind.MODEL_TURN));
        verifyNoInteractions(delegate);
    }

    @Test
    void supervisedTurnTimeoutAtCompletionBoundaryMustContinueToEngineeringValidation() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{}")
                .build();
        memoryStore.updateMessages(11L, List.of(
                UserMessage.from("keep current project"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        ));
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{new CountingSnapshotTool()});
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, new SilentStreamingModel());
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor,
                shortWindowStageAdmissionService());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        GenerationExecutionContext executionContext = shortWindowExecutionContext();
        assumeWorkspaceMutation(executionContext);

        List<GenerationStreamEvent> events = engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        state(executionContext, checkpoint, memoryStore.getMessages(11L)),
                        executionContext,
                        () -> false,
                        ignored -> { })
                .collectList()
                .block(Duration.ofSeconds(2));

        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.AGENT_EVENT.equals(event.getType())
                        && "reserved_completion".equals(event.getData().get("status"))));
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())
                        && "codegen_done".equals(event.getData().get("stage"))));
        assertEquals(1, executionContext.used(GenerationBudgetKind.MODEL_TURN));
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
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                approvalService, new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);

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
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);

        engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        state(executionContext, checkpoint, memoryStore.getMessages(11L)), executionContext,
                        () -> false, ignored -> { })
                .blockLast();

        assertEquals(2, model.calls.get());
        assertEquals(1, tool.executions.get());
        assertEquals(2, executionContext.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(0, executionContext.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
    }

    @Test
    void approvalContinuationMustUseThePersistedToolRoundBudget() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{}")
                .build();
        List<ChatMessage> transcript = List.of(
                UserMessage.from("完成当前修改"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        );
        memoryStore.updateMessages(11L, transcript);
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{new CountingSnapshotTool()});
        FinalizationStreamingModel model = new FinalizationStreamingModel();
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, model);
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);
        GenerationPerformanceProfile oneToolRound = new GenerationPerformanceProfile(
                GenerationPerformanceProfile.ModelTier.BALANCED,
                false,
                1,
                "审批恢复预算测试"
        );

        engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        state(executionContext, checkpoint, transcript, oneToolRound),
                        executionContext,
                        () -> false,
                        ignored -> { })
                .blockLast();

        assertEquals(1, model.calls.get());
        assertEquals(ToolChoice.NONE, model.request.get().toolChoice());
        assertTrue(model.request.get().toolSpecifications().isEmpty());
        assertEquals(1, executionContext.agentToolRoundLimit());
        assertEquals(2, executionContext.agentModelTurnsStarted());
    }

    @Test
    void exitToolMustCompleteContinuationWithoutAnotherModelTurn() {
        InMemoryChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ToolExecutionRequest pendingRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{}")
                .build();
        memoryStore.updateMessages(11L, List.of(
                UserMessage.from("完成后退出"),
                AiMessage.builder().toolExecutionRequests(List.of(pendingRequest)).build()
        ));
        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.getToolsForCodeGen(CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(new BaseTool[]{new CountingSnapshotTool(), new ExitTool()});
        ExitStreamingModel model = new ExitStreamingModel();
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        stubExecutionModel(modelFactory, model);
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, modelFactory, mock(ToolExecutionFailurePolicy.class),
                mock(ToolApprovalService.class), new GenerationToolExecutionContextService(),
                passthroughPolicy(), modelCallSupervisor, stageAdmissionService());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                pendingRequest.id(), pendingRequest.name(), pendingRequest.arguments(),
                "{\"taskId\":\"task-1\"}", NOW);
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);

        engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        state(executionContext, checkpoint, memoryStore.getMessages(11L)),
                        executionContext, () -> false, ignored -> { })
                .blockLast();

        assertEquals(1, model.calls.get());
        assertEquals(1, executionContext.used(GenerationBudgetKind.MODEL_TURN));
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
        DefaultGenerationAgentRuntime engine = new DefaultGenerationAgentRuntime(
                memoryStore, toolManager, mock(StreamingModelFactory.class),
                mock(ToolExecutionFailurePolicy.class), mock(ToolApprovalService.class),
                new GenerationToolExecutionContextService(), passthroughPolicy(),
                modelCallSupervisor, stageAdmissionService());
        GenerationExecutionContext executionContext = executionContext();

        assertThrows(IllegalStateException.class, () -> engine.continueAfterDecision(
                        approval(ToolApprovalStatus.REJECTED, checkpoint),
                        legacyState(executionContext), executionContext,
                        () -> false, ignored -> { })
                .blockLast());
    }

    private ToolApprovalRecord approval(ToolApprovalStatus status,
                                        ToolInvocationCheckpoint checkpoint) {
        return approval(status, checkpoint, DestructiveToolAction.SNAPSHOT_ROLLBACK);
    }

    private ToolApprovalRecord approval(
            ToolApprovalStatus status,
            ToolInvocationCheckpoint checkpoint,
            DestructiveToolAction action
    ) {
        return new ToolApprovalRecord(
                "a".repeat(64), "task-1", 11L, 7L,
                action, "{}", status,
                NOW.minusSeconds(10), NOW.plusSeconds(600),
                7L, NOW, null, 2L, checkpoint);
    }

    private void stubExecutionModel(StreamingModelFactory modelFactory,
                                    StreamingChatModel delegate) {
        when(modelFactory.createExecutionModel(any(), any(), any(), any())).thenAnswer(invocation -> {
            Runnable beforeModelTurn = invocation.getArgument(2);
            return new StreamingChatModel() {
                @Override
                public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    if (beforeModelTurn != null) {
                        beforeModelTurn.run();
                    }
                    delegate.doChat(request, handler);
                }
            };
        });
    }

    private AiToolInvocationPolicy passthroughPolicy() {
        AiToolInvocationPolicy policy = mock(AiToolInvocationPolicy.class);
        when(policy.governModelTurn(any(String.class), anyInt(), any(ChatRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        return policy;
    }

    /** 本组用例聚焦模型循环行为，预置已由工具网关确认的工作区变更。 */
    private void assumeWorkspaceMutation(GenerationExecutionContext executionContext) {
        executionContext.recordSuccessfulWorkspaceMutations(1);
    }

    private GenerationExecutionContext executionContextWithMutation() {
        GenerationExecutionContext executionContext = executionContext();
        assumeWorkspaceMutation(executionContext);
        return executionContext;
    }

    private GenerationExecutionContext executionContext() {
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-1", 11L, 7L, NOW.minusSeconds(30),
                runtimeProperties.toLimits(), Clock.fixed(NOW, ZoneOffset.UTC));
        context.bindExecutionFence(new GenerationExecutionFence("task-1", "test-worker", 1L));
        return context;
    }

    private GenerationExecutionContext expiringExecutionContext() {
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-1", 11L, 7L, NOW.minus(Duration.ofMinutes(9)),
                runtimeProperties.toLimits(), Clock.fixed(NOW, ZoneOffset.UTC));
        context.bindExecutionFence(new GenerationExecutionFence("task-1", "test-worker", 1L));
        return context;
    }

    private GenerationExecutionContext shortWindowExecutionContext() {
        Map<GenerationBudgetKind, Integer> budgets = new java.util.EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-1",
                11L,
                7L,
                NOW,
                new com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits(
                        Duration.ofMillis(200),
                        Duration.ofMillis(200),
                        Duration.ofMillis(1),
                        budgets
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        context.bindExecutionFence(new GenerationExecutionFence("task-1", "test-worker", 1L));
        return context;
    }

    private GenerationStageAdmissionService stageAdmissionService() {
        return new GenerationStageAdmissionService(
                new GenerationStageAdmissionProperties(),
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                new GenerationPerformanceMonitorService()
        );
    }

    /** 构造与生产一致的短期记忆窗口策略。 */
    private AgentConversationWindowPolicy windowPolicy() {
        return new AgentConversationWindowPolicy(
                new AgentConversationFolder(new ToolRoundPathExtractor(new ObjectMapper())),
                new AgentConversationTokenAccountant(
                        new OpenAiCompatibleContextTokenEstimator(
                                new AiContextPackBudgetProperties())));
    }

    private GenerationStageAdmissionService shortWindowStageAdmissionService() {
        GenerationStageAdmissionProperties properties = new GenerationStageAdmissionProperties();
        properties.setModelTurnMinimum(Duration.ofMillis(10));
        properties.setModelHandoffReserve(Duration.ofMillis(10));
        properties.setRepairModelMinimum(Duration.ofMillis(10));
        properties.setBuildMinimum(Duration.ofMillis(10));
        properties.setRuntimeValidationMinimum(Duration.ofMillis(10));
        properties.setTerminalizationReserve(Duration.ofMillis(10));
        return new GenerationStageAdmissionService(
                properties,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                new GenerationPerformanceMonitorService()
        );
    }

    private GenerationToolContinuationState state(GenerationExecutionContext context,
                                                  ToolInvocationCheckpoint checkpoint,
                                                  List<ChatMessage> transcript) {
        return state(
                context,
                checkpoint,
                transcript,
                GenerationPerformanceProfile.balanced()
        );
    }

    private GenerationToolContinuationState state(GenerationExecutionContext context,
                                                   ToolInvocationCheckpoint checkpoint,
                                                   List<ChatMessage> transcript,
                                                   GenerationPerformanceProfile profile) {
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
                CodeGenTypeEnum.VUE_PROJECT, profile,
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
        return executionApproval(
                status, checkpoint, outcome, DestructiveToolAction.SNAPSHOT_ROLLBACK);
    }

    private ToolApprovalRecord executionApproval(
            ToolApprovalStatus status,
            ToolInvocationCheckpoint checkpoint,
            ToolExecutionOutcome outcome,
            DestructiveToolAction action
    ) {
        return new ToolApprovalRecord(
                "a".repeat(64), "task-1", 11L, 7L,
                action, "{}", status,
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

    private static final class ThinkingStreamingModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            StreamingHandle handle = mock(StreamingHandle.class);
            handler.onPartialThinking(
                    new PartialThinking("private chain of thought"),
                    new PartialThinkingContext(handle));
            handler.onPartialResponse(
                    new PartialResponse("visible answer"),
                    new PartialResponseContext(handle));
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .build());
        }
    }

    private static final class SilentStreamingModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            // 由调用监督器触发受保护完成边界。
        }
    }

    private static final class FinalizationStreamingModel implements StreamingChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ChatRequest> request = new AtomicReference<>();

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            this.calls.incrementAndGet();
            this.request.set(request);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("已完成"))
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

    private static final class ApprovalStreamingModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                    .id("call-approval")
                                    .name("manageSnapshot")
                                    .arguments("{\"action\":\"rollbackSnapshot\"}")
                                    .build()))
                            .build())
                    .build());
        }
    }

    private static final class ExitStreamingModel implements StreamingChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            calls.incrementAndGet();
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                    .id("exit-1")
                                    .name("exitTool")
                                    .arguments("{}")
                                    .build()))
                            .build())
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

    private static final class CountingDeleteTool extends BaseTool {
        private final AtomicInteger executions = new AtomicInteger();

        @Tool
        public TextContent deleteFile(
                @P("relativeFilePath") String relativeFilePath,
                @ToolMemoryId Long appId
        ) {
            executions.incrementAndGet();
            return ToolResultEvidence.effectiveMutations(
                    "文件删除成功: " + relativeFilePath,
                    List.of(relativeFilePath)
            );
        }

        @Override
        public ToolRiskLevel getRiskLevel() {
            return ToolRiskLevel.DESTRUCTIVE;
        }

        @Override
        public boolean canMutateWorkspace() {
            return true;
        }

        @Override
        public String getToolName() {
            return "deleteFile";
        }

        @Override
        public String getDisplayName() {
            return "delete";
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            return "delete";
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
