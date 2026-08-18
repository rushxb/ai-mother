package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationAgentTurnPolicyTest {

    private static final CodeGenTypeEnum TYPE = CodeGenTypeEnum.VUE_PROJECT;
    private static final GenerationPerformanceProfile PROFILE =
            new GenerationPerformanceProfile(
                    GenerationPerformanceProfile.ModelTier.BALANCED,
                    false,
                    2,
                    "测试工具回合预算"
            );

    @Test
    void fullStackRepairProfileMustRetainProjectToolRoundFloor() {
        Fixture fixture = fixture();

        fixture.policy.beginAttempt(
                fixture.context,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                GenerationPerformanceProfile.qualityFirst()
        );

        assertEquals(20, fixture.context.agentToolRoundLimit());
    }

    @Test
    void finalModelTurnMustDisableToolsAndFailClosedOnFurtherCalls() {
        Fixture fixture = fixture();
        fixture.policy.beginAttempt(11L, TYPE, PROFILE);

        GenerationAgentTurnPolicy.PreparedModelTurn first = fixture.policy.prepareModelTurn(
                fixture.context, TYPE, PROFILE, request());
        GenerationAgentTurnPolicy.PreparedModelTurn second = fixture.policy.prepareModelTurn(
                fixture.context, TYPE, PROFILE, request());
        GenerationAgentTurnPolicy.PreparedModelTurn finalTurn = fixture.policy.prepareModelTurn(
                fixture.context, TYPE, PROFILE, request());

        assertTrue(first.toolCallsAllowed());
        assertTrue(second.toolCallsAllowed());
        assertFalse(finalTurn.toolCallsAllowed());
        assertEquals(3, finalTurn.modelTurn());
        assertEquals(ToolChoice.NONE, finalTurn.request().toolChoice());
        assertTrue(finalTurn.request().toolSpecifications().isEmpty());
        assertTrue(finalTurn.request().messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .anyMatch(message -> message.text().contains("工具回合预算已经用完")));
        assertThrows(GenerationExecutionPolicyException.class,
                () -> fixture.policy.assertToolExecutionAllowed(fixture.context));
        assertThrows(GenerationExecutionPolicyException.class,
                () -> fixture.policy.prepareModelTurn(
                        fixture.context, TYPE, PROFILE, request()));
    }

    @Test
    void approvalRestoreMustContinueThePersistedAttemptInsteadOfResettingBudget() {
        Fixture fixture = fixture();
        fixture.policy.beginAttempt(11L, TYPE, PROFILE);
        fixture.policy.prepareModelTurn(fixture.context, TYPE, PROFILE, request());

        GenerationExecutionContext restored = GenerationExecutionContext.restore(
                fixture.context.snapshot(), fixture.context.limits(), Clock.systemUTC());
        fixture.policy.restoreAttempt(
                restored,
                TYPE,
                PROFILE,
                pendingConversation()
        );
        GenerationAgentTurnPolicy.PreparedModelTurn continued = fixture.policy.prepareModelTurn(
                restored, TYPE, PROFILE, request());

        assertEquals(2, continued.modelTurn());
        assertTrue(continued.toolCallsAllowed());
        assertEquals(1L, restored.agentAttemptEpoch());
    }

    @Test
    void newRootAttemptMustResetOnlyTheAttemptLocalCounter() {
        Fixture fixture = fixture();
        fixture.policy.beginAttempt(11L, TYPE, PROFILE);
        fixture.policy.prepareModelTurn(fixture.context, TYPE, PROFILE, request());

        fixture.policy.beginAttempt(11L, TYPE, PROFILE);
        GenerationAgentTurnPolicy.PreparedModelTurn retried = fixture.policy.prepareModelTurn(
                fixture.context, TYPE, PROFILE, request());

        assertEquals(1, retried.modelTurn());
        assertEquals(2L, fixture.context.agentAttemptEpoch());
    }

    private Fixture fixture() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        GenerationExecutionContextService contextService =
                new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contextService.start("task-1", 11L, 7L);
        return new Fixture(
                context,
                new GenerationAgentTurnPolicy(contextService)
        );
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("系统提示"),
                        UserMessage.from("生成网站")
                ))
                .toolSpecifications(List.of(ToolSpecification.builder()
                        .name("writeFile")
                        .description("写入文件")
                        .build()))
                .build();
    }

    private List<ChatMessage> pendingConversation() {
        return List.of(
                UserMessage.from("生成网站"),
                AiMessage.builder()
                        .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("writeFile")
                                .arguments("{}")
                                .build()))
                        .build()
        );
    }

    private record Fixture(
            GenerationExecutionContext context,
            GenerationAgentTurnPolicy policy
    ) {
    }
}
