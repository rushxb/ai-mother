package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.config.AiAgentProductivityProperties;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationAgentProductivityGuardTest {

    private static final String TASK_ID = "task-productivity";

    private AiAgentProductivityProperties properties;
    private ToolManager toolManager;
    private GenerationOrchestrationMetricsCollector metricsCollector;

    @BeforeEach
    void setUp() {
        properties = new AiAgentProductivityProperties();
        properties.setMaxReadOnlyCallsWithoutMutation(2);
        properties.setMaxModelTurnsWithoutMutation(8);
        properties.setForcedActionTurnsBeforeFinalize(1);
        toolManager = mock(ToolManager.class);
        metricsCollector = mock(GenerationOrchestrationMetricsCollector.class);
        registerTool("readFile", ToolRiskLevel.READ_ONLY);
        registerTool("writeFile", ToolRiskLevel.WRITE);
        registerTool("exitTool", ToolRiskLevel.READ_ONLY);
    }

    @Test
    void readOnlyStallWithoutAnyMutationMustRequireAWriteTool() {
        GenerationAgentProductivityGuard guard = guard();
        ChatRequest request = request();

        assertEquals(request, guard.governModelTurn(TASK_ID, 0, request));
        guard.recordToolCompletion(TASK_ID, "readFile");
        guard.recordToolCompletion(TASK_ID, "readFile");

        ChatRequest governed = guard.governModelTurn(TASK_ID, 0, request);

        assertEquals(ToolChoice.REQUIRED, governed.toolChoice());
        assertEquals(List.of("writeFile"), governed.toolSpecifications().stream()
                .map(ToolSpecification::name)
                .toList());
        assertTrue(systemText(governed).contains("工作区尚无任何成功修改"));
        verify(metricsCollector).recordAgentProductivityIntervention(
                GenerationAgentProductivityGuard.ACTION_FORCE,
                GenerationAgentProductivityGuard.REASON_READ_ONLY);
    }

    @Test
    void aSuccessfulMutationMustResetTheProductivityWindow() {
        GenerationAgentProductivityGuard guard = guard();
        ChatRequest request = request();
        guard.governModelTurn(TASK_ID, 0, request);
        guard.recordToolCompletion(TASK_ID, "readFile");
        guard.recordToolCompletion(TASK_ID, "readFile");
        guard.governModelTurn(TASK_ID, 0, request);
        guard.recordToolCompletion(TASK_ID, "writeFile");

        ChatRequest governed = guard.governModelTurn(TASK_ID, 1, request);

        assertEquals(request, governed);
        assertEquals(3, governed.toolSpecifications().size());
        assertFalse(systemText(governed).contains("运行时生产率约束"));
    }

    @Test
    void existingMutationsMayFinalizeAfterForcedTurnsStillMakeNoProgress() {
        GenerationAgentProductivityGuard guard = guard();
        ChatRequest request = request();
        guard.governModelTurn(TASK_ID, 0, request);
        guard.recordToolCompletion(TASK_ID, "writeFile");
        guard.governModelTurn(TASK_ID, 1, request);
        guard.recordToolCompletion(TASK_ID, "readFile");
        guard.recordToolCompletion(TASK_ID, "readFile");
        ChatRequest forced = guard.governModelTurn(TASK_ID, 1, request);
        assertTrue(systemText(forced).contains("禁止继续读取"));
        guard.recordToolCompletion(TASK_ID, "writeFile");

        ChatRequest finalized = guard.governModelTurn(TASK_ID, 1, request);

        assertEquals(ToolChoice.NONE, finalized.toolChoice());
        assertTrue(systemText(finalized).contains("工程流水线接管"));
        verify(metricsCollector).recordAgentProductivityIntervention(
                GenerationAgentProductivityGuard.ACTION_FINALIZE,
                GenerationAgentProductivityGuard.REASON_READ_ONLY);
    }

    @Test
    void aTaskWithoutSuccessfulMutationsMustNeverBeFinalizedByTheGuard() {
        GenerationAgentProductivityGuard guard = guard();
        ChatRequest request = request();
        guard.governModelTurn(TASK_ID, 0, request);
        guard.recordToolCompletion(TASK_ID, "readFile");
        guard.recordToolCompletion(TASK_ID, "readFile");
        guard.governModelTurn(TASK_ID, 0, request);
        guard.recordToolCompletion(TASK_ID, "writeFile");

        ChatRequest stillForced = guard.governModelTurn(TASK_ID, 0, request);

        assertEquals(ToolChoice.REQUIRED, stillForced.toolChoice());
        assertEquals(List.of("writeFile"), stillForced.toolSpecifications().stream()
                .map(ToolSpecification::name)
                .toList());
    }

    @Test
    void durableTranscriptMustRestoreTheReadOnlyStallWindow() {
        GenerationAgentProductivityGuard guard = guard();
        ToolExecutionRequest first = toolRequest("call-1", "readFile");
        ToolExecutionRequest second = toolRequest("call-2", "readFile");
        List<ChatMessage> transcript = List.of(
                UserMessage.from("检查项目"),
                AiMessage.builder().toolExecutionRequests(List.of(first, second)).build(),
                ToolExecutionResultMessage.from(first, "A"),
                ToolExecutionResultMessage.from(second, "B")
        );

        guard.restore(TASK_ID, transcript, 0);
        ChatRequest governed = guard.governModelTurn(TASK_ID, 0, request());

        assertEquals(ToolChoice.REQUIRED, governed.toolChoice());
        assertTrue(systemText(governed).contains("累计探索型只读调用 2 次"));
    }

    private GenerationAgentProductivityGuard guard() {
        return new GenerationAgentProductivityGuard(
                properties,
                toolManager,
                mock(GenerationExecutionContextService.class),
                metricsCollector
        );
    }

    private void registerTool(String name, ToolRiskLevel riskLevel) {
        BaseTool tool = mock(BaseTool.class);
        when(tool.getRiskLevel()).thenReturn(riskLevel);
        when(tool.canMutateWorkspace()).thenReturn(riskLevel == ToolRiskLevel.WRITE);
        when(toolManager.getTool(name)).thenReturn(tool);
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("你是编程助手"),
                        UserMessage.from("完成应用")
                ))
                .toolSpecifications(List.of(
                        specification("readFile"),
                        specification("writeFile"),
                        specification("exitTool")
                ))
                .build();
    }

    private ToolSpecification specification(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private ToolExecutionRequest toolRequest(String id, String name) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments("{}")
                .build();
    }

    private String systemText(ChatRequest request) {
        return SystemMessage.findFirst(request.messages()).orElseThrow().text();
    }
}
