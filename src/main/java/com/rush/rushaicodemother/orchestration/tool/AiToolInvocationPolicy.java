package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.tools.ApprovalGatedTool;
import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 在每次自主人工智能工具调用之前执行中央、故障关闭的功能边界。 */
@Component
@RequiredArgsConstructor
public class AiToolInvocationPolicy {

    private final ToolManager toolManager;
    private final GenerationToolExecutionContextService executionContextService;
    private final ToolExecutionFailurePolicy failurePolicy;
    private final GenerationToolLoopGuard toolLoopGuard;
    private final GenerationAgentProductivityGuard productivityGuard;

    /**
 * 处理授权。
 *
 * @param event 待处理的领域事件
 * @param expectedCodeGenType {@code expectedCodeGenType} 对应的调用参数
 * @param profile 配置档
 */
    public void authorize(BeforeToolExecution event,
                          CodeGenTypeEnum expectedCodeGenType,
                          GenerationPerformanceProfile profile) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (event == null || event.request() == null || event.invocationContext() == null) {
            reject("invocation_context_missing");
        }
        ToolExecutionRequest request = event.request();
        String toolName = request.name();
        if (toolName == null || !toolName.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
            reject("tool_name_invalid");
        }
        Long appId = positiveAppId(event.invocationContext().chatMemoryId());
        GenerationToolExecutionContext context = executionContextService.getContextForInvocation(
                        event.invocationContext())
                .orElseThrow(() -> violation("generation_context_missing"));
        if (!Objects.equals(appId, context.appId())
                || context.taskId() == null || context.taskId().isBlank()) {
            reject("generation_context_mismatch");
        }
        if (expectedCodeGenType == null || context.codeGenType() != expectedCodeGenType) {
            reject("codegen_type_mismatch");
        }

        BaseTool tool = toolManager.getTool(toolName);
        if (tool == null || !toolManager.isToolAllowedForCodeGen(toolName, expectedCodeGenType)) {
            reject("tool_not_allowed");
        }
        if (tool.getRiskLevel() == ToolRiskLevel.EXTERNAL_SIDE_EFFECT) {
            reject("external_side_effect_denied");
        }
        verifyApprovedInvocationIntegrity(context.taskId(), request);

        if (tool.getRiskLevel() != ToolRiskLevel.DESTRUCTIVE) {
            authorizeExecution(context, request);
            return;
        }
        if (!(tool instanceof ApprovalGatedTool)) {
            reject("destructive_approval_contract_missing");
        }
        ApprovalGatedTool approvalGatedTool = (ApprovalGatedTool) tool;
        if (request.id() == null || request.id().isBlank()) {
            reject("destructive_request_id_missing");
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            approvalGatedTool.authorizeInvocation(request, appId);
        } catch (GenerationApprovalRequiredException approvalRequired) {
            failurePolicy.prepareApprovalSuspension(
                    approvalRequired,
                    request,
                    expectedCodeGenType,
                    profile,
                    event.invocationContext().userMessage());
            throw approvalRequired;
        }
        authorizeExecution(context, request);
    }

    private void authorizeExecution(GenerationToolExecutionContext context,
                                    ToolExecutionRequest request) {
        toolLoopGuard.beforeInvocation(context.taskId(), request);
        // 工具实现通过当前线程绑定的精确 fence 解析工作区，完成回调必须清理该绑定。
        executionContextService.activateFence(context.executionFence());
    }

    /** 记录工具结果并清理 LangChain4j 工具线程上的精确 fence。 */
    public void complete(ToolExecution execution) {
        try {
            if (execution == null || execution.request() == null) {
                return;
            }
            executionContextService.getContextForInvocation(execution.invocationContext())
                    .ifPresent(context -> {
                        toolLoopGuard.completeInvocation(
                                context.taskId(), execution.request(),
                                execution.result(), execution.hasFailed());
                        productivityGuard.recordToolCompletion(
                                context.taskId(), execution.request().name());
                    });
        } finally {
            executionContextService.clearActiveFence();
        }
    }

    /**
 * 处理恢复循环状态。
 *
 * @param taskId 任务编号
 * @param messages 消息列表
 * @param successfulWorkspaceMutations 待处理的 {@code successfulWorkspaceMutations} 集合
 */
    public void restoreLoopState(String taskId,
                                 List<ChatMessage> messages,
                                 int successfulWorkspaceMutations) {
        toolLoopGuard.restore(taskId, messages);
        productivityGuard.restore(taskId, messages, successfulWorkspaceMutations);
    }

    public ChatRequest governModelTurn(Long appId, ChatRequest request) {
        return productivityGuard.governModelTurn(appId, request);
    }

    public ChatRequest governModelTurn(String taskId,
                                       int successfulWorkspaceMutations,
                                       ChatRequest request) {
        return productivityGuard.governModelTurn(
                taskId, successfulWorkspaceMutations, request);
    }

    private void verifyApprovedInvocationIntegrity(String taskId, ToolExecutionRequest request) {
        executionContextService.currentInvocation().ifPresent(invocation -> {
            String arguments = request.arguments() == null ? "" : request.arguments();
            if (!taskId.equals(invocation.taskId())
                    || !Objects.equals(request.id(), invocation.requestId())
                    || !Objects.equals(request.name(), invocation.toolName())
                    || !DigestUtil.sha256Hex(arguments).equals(invocation.argumentsDigest())) {
                reject("approved_invocation_mismatch");
            }
        });
    }

    private Long positiveAppId(Object memoryId) {
        if (memoryId instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        throw violation("app_identity_missing");
    }

    private void reject(String violationCode) {
        throw violation(violationCode);
    }

    private ToolPolicyViolationException violation(String violationCode) {
        return new ToolPolicyViolationException(violationCode);
    }
}
