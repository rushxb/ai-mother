package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.core.error.GenerationAgentLoopException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将工具故障转换为安全模型结果或持久的批准暂停信号。 */
@Component
@RequiredArgsConstructor
public class ToolExecutionFailurePolicy {

    private final ToolApprovalService toolApprovalService;
    private final ToolInvocationCheckpointFactory checkpointFactory;

    public ToolErrorHandlerResult handle(Throwable failure,
                                         ToolErrorContext context,
                                         CodeGenTypeEnum codeGenType,
                                         GenerationPerformanceProfile profile) {
        GenerationAgentLoopException loopFailure = findAgentLoop(failure);
        if (loopFailure != null) {
            throw loopFailure;
        }
        GenerationExecutionPolicyException policyFailure = findExecutionPolicyFailure(failure);
        if (policyFailure != null) {
            throw policyFailure;
        }
        GenerationApprovalRequiredException approvalRequired = findApprovalRequired(failure);
        if (approvalRequired == null) {
            return ToolErrorHandlerResult.text("Tool execution failed. Inspect the inputs and choose a safe alternative.");
        }
        ToolExecutionRequest request = context == null ? null : context.toolExecutionRequest();
        UserMessage currentUserMessage = context == null || context.invocationContext() == null
                ? null
                : context.invocationContext().userMessage();
        prepareApprovalSuspension(
                approvalRequired, request, codeGenType, profile, currentUserMessage);
        throw approvalRequired;
    }

    public void prepareApprovalSuspension(GenerationApprovalRequiredException approvalRequired,
                                           ToolExecutionRequest request,
                                           CodeGenTypeEnum codeGenType,
                                           GenerationPerformanceProfile profile) {
        prepareApprovalSuspension(approvalRequired, request, codeGenType, profile, null);
    }

    public void prepareApprovalSuspension(GenerationApprovalRequiredException approvalRequired,
                                          ToolExecutionRequest request,
                                          CodeGenTypeEnum codeGenType,
                                          GenerationPerformanceProfile profile,
                                          UserMessage currentUserMessage) {
        if (approvalRequired == null) {
            throw new IllegalArgumentException("approval signal is required");
        }
        if (request == null || request.id() == null || request.name() == null) {
            throw new IllegalStateException("approval-gated tool invocation identity is missing", approvalRequired);
        }
        ToolInvocationCheckpoint checkpoint = checkpointFactory.capture(
                approvalRequired.taskId(),
                request.id(),
                request.name(),
                request.arguments(),
                codeGenType,
                profile,
                currentUserMessage
        );
        toolApprovalService.requestApproval(
                approvalRequired.taskId(),
                approvalRequired.action(),
                approvalRequired.approvalId(),
                approvalRequired.requestDetails(),
                checkpoint
        );
    }

    private GenerationApprovalRequiredException findApprovalRequired(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof GenerationApprovalRequiredException approvalRequired) {
                return approvalRequired;
            }
            current = current.getCause();
        }
        return null;
    }

    private GenerationAgentLoopException findAgentLoop(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof GenerationAgentLoopException loopFailure) {
                return loopFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private GenerationExecutionPolicyException findExecutionPolicyFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof GenerationExecutionPolicyException policyFailure) {
                return policyFailure;
            }
            current = current.getCause();
        }
        return null;
    }
}
