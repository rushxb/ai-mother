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

    /**
 * 处理工具执行失败策略。
 *
 * @param failure 失败
 * @param context 执行上下文
 * @param codeGenType 代码生成类型
 * @param profile 配置档
 * @return 工具执行失败策略
 */
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
            return ToolErrorHandlerResult.text("工具执行失败，请检查输入并选择安全的替代方案。");
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

    /**
 * 准备后续流程所需的审批{@code Suspension}。
 *
 * @param approvalRequired {@code approvalRequired} 对应的调用参数
 * @param request 请求参数
 * @param codeGenType 代码生成类型
 * @param profile 配置档
 * @param currentUserMessage 当前用户消息
 */
    public void prepareApprovalSuspension(GenerationApprovalRequiredException approvalRequired,
                                          ToolExecutionRequest request,
                                          CodeGenTypeEnum codeGenType,
                                          GenerationPerformanceProfile profile,
                                          UserMessage currentUserMessage) {
        if (approvalRequired == null) {
            throw new IllegalArgumentException("审批信号不能为空");
        }
        if (request == null || request.id() == null || request.name() == null) {
            throw new IllegalStateException("审批工具调用缺少请求标识", approvalRequired);
        }
        synchronized (approvalRequired) {
            if (approvalRequired.suspensionPrepared()) {
                return;
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
            approvalRequired.markSuspensionPrepared();
        }
    }

    /** 查找匹配的审批{@code Required}。 */
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

    /** 查找匹配的智能体循环。 */
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

    /** 查找匹配的执行策略失败。 */
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
