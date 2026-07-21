package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.tools.ApprovalGatedTool;
import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.BeforeToolExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Central, fail-closed capability boundary executed before every autonomous AI tool call. */
@Component
@RequiredArgsConstructor
public class AiToolInvocationPolicy {

    private final ToolManager toolManager;
    private final GenerationToolExecutionContextService executionContextService;
    private final ToolExecutionFailurePolicy failurePolicy;

    public void authorize(BeforeToolExecution event,
                          CodeGenTypeEnum expectedCodeGenType,
                          GenerationPerformanceProfile profile) {
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
            executionContextService.activateFence(context.executionFence());
            return;
        }
        if (!(tool instanceof ApprovalGatedTool)) {
            reject("destructive_approval_contract_missing");
        }
        ApprovalGatedTool approvalGatedTool = (ApprovalGatedTool) tool;
        if (request.id() == null || request.id().isBlank()) {
            reject("destructive_request_id_missing");
        }
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
        // Tool implementations resolve their workspace through this thread-bound fence. The
        // matching afterToolExecution callback clears it even when the executor uses a pool thread.
        executionContextService.activateFence(context.executionFence());
    }

    /** Clears the per-thread exact-fence binding after LangChain4j finishes a tool invocation. */
    public void clearActiveInvocation() {
        executionContextService.clearActiveFence();
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
