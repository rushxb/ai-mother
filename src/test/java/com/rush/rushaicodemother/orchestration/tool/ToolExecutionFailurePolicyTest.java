package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.core.error.GenerationAgentLoopException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionFailurePolicyTest {

    @Test
    void approvalFailureMustPersistRequestAndExactInvocationBeforePropagation() {
        ToolApprovalService approvals = mock(ToolApprovalService.class);
        ToolInvocationCheckpointFactory checkpoints = mock(ToolInvocationCheckpointFactory.class);
        ToolExecutionFailurePolicy policy = new ToolExecutionFailurePolicy(approvals, checkpoints);
        String approvalId = "a".repeat(64);
        GenerationApprovalRequiredException required = new GenerationApprovalRequiredException(
                "task-1",
                DestructiveToolAction.SNAPSHOT_ROLLBACK,
                approvalId,
                Map.of("snapshotName", "safe")
        );
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("manageSnapshot")
                .arguments("{\"action\":\"rollbackSnapshot\"}")
                .build();
        ToolErrorContext context = mock(ToolErrorContext.class);
        when(context.toolExecutionRequest()).thenReturn(request);
        InvocationContext invocationContext = mock(InvocationContext.class);
        UserMessage currentUserMessage = UserMessage.from("build a dashboard");
        when(invocationContext.userMessage()).thenReturn(currentUserMessage);
        when(context.invocationContext()).thenReturn(invocationContext);
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                request.id(), request.name(), request.arguments(), "{\"taskId\":\"task-1\"}",
                Instant.parse("2026-07-16T12:00:00Z"));
        GenerationPerformanceProfile profile = GenerationPerformanceProfile.qualityFirst();
        when(checkpoints.capture(
                "task-1", request.id(), request.name(), request.arguments(),
                CodeGenTypeEnum.VUE_PROJECT, profile, currentUserMessage)).thenReturn(checkpoint);

        GenerationApprovalRequiredException propagated = assertThrows(
                GenerationApprovalRequiredException.class,
                () -> policy.handle(required, context, CodeGenTypeEnum.VUE_PROJECT, profile));

        assertSame(required, propagated);
        var order = inOrder(approvals, checkpoints);
        order.verify(checkpoints).capture(
                "task-1", request.id(), request.name(), request.arguments(),
                CodeGenTypeEnum.VUE_PROJECT, profile, currentUserMessage);
        order.verify(approvals).requestApproval(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                approvalId, Map.of("snapshotName", "safe"), checkpoint);
    }

    @Test
    void ordinaryToolFailureMustReturnSanitizedModelResult() {
        ToolExecutionFailurePolicy policy = new ToolExecutionFailurePolicy(
                mock(ToolApprovalService.class), mock(ToolInvocationCheckpointFactory.class));

        ToolErrorHandlerResult result = policy.handle(
                new IllegalStateException("secret-token"),
                mock(ToolErrorContext.class),
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationPerformanceProfile.balanced());

        assertEquals("工具执行失败，请检查输入并选择安全的替代方案。", result.text());
    }

    @Test
    void agentLoopFailureMustNotBeConvertedIntoAnotherModelTurn() {
        ToolExecutionFailurePolicy policy = new ToolExecutionFailurePolicy(
                mock(ToolApprovalService.class), mock(ToolInvocationCheckpointFactory.class));
        GenerationAgentLoopException loopFailure =
                new GenerationAgentLoopException("no_progress", "readFile");

        GenerationAgentLoopException propagated = assertThrows(
                GenerationAgentLoopException.class,
                () -> policy.handle(
                        new IllegalStateException("wrapped", loopFailure),
                        mock(ToolErrorContext.class),
                        CodeGenTypeEnum.VUE_PROJECT,
                        GenerationPerformanceProfile.balanced()));

        assertSame(loopFailure, propagated);
    }

    @Test
    void executionPolicyFailureMustNotBeConvertedIntoAnotherModelTurn() {
        ToolExecutionFailurePolicy policy = new ToolExecutionFailurePolicy(
                mock(ToolApprovalService.class), mock(ToolInvocationCheckpointFactory.class));
        GenerationExecutionPolicyException policyFailure =
                new GenerationExecutionPolicyException("工具写入预算已耗尽");

        GenerationExecutionPolicyException propagated = assertThrows(
                GenerationExecutionPolicyException.class,
                () -> policy.handle(
                        new IllegalStateException("wrapped", policyFailure),
                        mock(ToolErrorContext.class),
                        CodeGenTypeEnum.VUE_PROJECT,
                        GenerationPerformanceProfile.balanced()));

        assertSame(policyFailure, propagated);
    }
}
