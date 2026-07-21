package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.tools.ApprovalGatedTool;
import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.BeforeToolExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiToolInvocationPolicyTest {

    private static final Long APP_ID = 11L;
    private static final String TASK_ID = "task-1";

    private ToolManager toolManager;
    private GenerationToolExecutionContextService executionContexts;
    private ToolExecutionFailurePolicy failurePolicy;
    private AiToolInvocationPolicy policy;

    @BeforeEach
    void setUp() {
        toolManager = mock(ToolManager.class);
        executionContexts = new GenerationToolExecutionContextService();
        failurePolicy = mock(ToolExecutionFailurePolicy.class);
        policy = new AiToolInvocationPolicy(toolManager, executionContexts, failurePolicy);
    }

    @Test
    void missingGenerationContextMustFailClosed() {
        ToolPolicyViolationException violation = assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.authorize(event(request("readProject", "{}")),
                        CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced()));

        assertEquals("generation_context_missing", violation.violationCode());
    }

    @Test
    void codeGenerationTypeMismatchMustFailClosed() {
        bindContext(CodeGenTypeEnum.BACKEND_PROJECT);

        ToolPolicyViolationException violation = assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.authorize(event(request("readProject", "{}")),
                        CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced()));

        assertEquals("codegen_type_mismatch", violation.violationCode());
    }

    @Test
    void externalSideEffectToolMustBeDeniedAtInvocationBoundary() {
        bindContext(CodeGenTypeEnum.VUE_PROJECT);
        BaseTool tool = new TestTool("installPackage", ToolRiskLevel.EXTERNAL_SIDE_EFFECT);
        allow(tool);

        ToolPolicyViolationException violation = assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.authorize(event(request(tool.getToolName(), "{}")),
                        CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced()));

        assertEquals("external_side_effect_denied", violation.violationCode());
    }

    @Test
    void destructiveToolMustInvokeItsApprovalHook() {
        bindContext(CodeGenTypeEnum.VUE_PROJECT);
        AtomicInteger authorizations = new AtomicInteger();
        BaseTool tool = new GatedTestTool("deleteFile", authorizations, null);
        allow(tool);

        policy.authorize(event(request(tool.getToolName(), "{\"relativeFilePath\":\"src/App.vue\"}")),
                CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced());

        assertEquals(1, authorizations.get());
    }

    @Test
    void approvedInvocationIdentityMismatchMustFailClosed() {
        bindContext(CodeGenTypeEnum.VUE_PROJECT);
        BaseTool tool = new GatedTestTool("deleteFile", new AtomicInteger(), null);
        allow(tool);
        ToolExecutionRequest request = request(tool.getToolName(), "{\"path\":\"safe.txt\"}");
        GenerationToolExecutionContextService.ToolInvocationExecution invocation =
                new GenerationToolExecutionContextService.ToolInvocationExecution(
                        TASK_ID, request.id(), request.name(), DigestUtil.sha256Hex("different"));

        ToolPolicyViolationException violation = assertThrows(
                ToolPolicyViolationException.class,
                () -> executionContexts.withInvocation(invocation, () -> {
                    policy.authorize(event(request), CodeGenTypeEnum.VUE_PROJECT,
                            GenerationPerformanceProfile.balanced());
                    return null;
                }));

        assertEquals("approved_invocation_mismatch", violation.violationCode());
    }

    @Test
    void policyViolationMustNotExposeToolArgumentsOrSecrets() {
        bindContext(CodeGenTypeEnum.VUE_PROJECT);
        String secret = "prod-password-do-not-leak";
        BaseTool tool = new TestTool("installPackage", ToolRiskLevel.EXTERNAL_SIDE_EFFECT);
        allow(tool);

        ToolPolicyViolationException violation = assertThrows(
                ToolPolicyViolationException.class,
                () -> policy.authorize(event(request(tool.getToolName(),
                                "{\"password\":\"" + secret + "\"}")),
                        CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced()));

        assertFalse(violation.getMessage().contains(secret));
        assertFalse(violation.toString().contains(secret));
        assertEquals(0, violation.getStackTrace().length);
    }

    @Test
    void approvalSignalMustCreateDurableSuspensionBeforePropagating() {
        bindContext(CodeGenTypeEnum.VUE_PROJECT);
        ToolExecutionRequest request = request("deleteFile", "{\"relativeFilePath\":\"src/App.vue\"}");
        GenerationApprovalRequiredException approvalRequired = new GenerationApprovalRequiredException(
                TASK_ID,
                DestructiveToolAction.FILE_DELETE,
                "a".repeat(64),
                Map.of("appId", APP_ID)
        );
        BaseTool tool = new GatedTestTool("deleteFile", new AtomicInteger(), approvalRequired);
        allow(tool);
        GenerationPerformanceProfile profile = GenerationPerformanceProfile.balanced();

        GenerationApprovalRequiredException propagated = assertThrows(
                GenerationApprovalRequiredException.class,
                () -> policy.authorize(event(request), CodeGenTypeEnum.VUE_PROJECT, profile));

        assertEquals(approvalRequired, propagated);
        verify(failurePolicy).prepareApprovalSuspension(
                approvalRequired,
                request,
                CodeGenTypeEnum.VUE_PROJECT,
                profile,
                UserMessage.from("test"));
    }

    private void bindContext(CodeGenTypeEnum codeGenType) {
        executionContexts.bindChangePlan(
                APP_ID, TASK_ID, "patch_first", codeGenType,
                null, false, "test");
    }

    private void allow(BaseTool tool) {
        when(toolManager.getTool(tool.getToolName())).thenReturn(tool);
        when(toolManager.isToolAllowedForCodeGen(tool.getToolName(), CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(true);
    }

    private BeforeToolExecution event(ToolExecutionRequest request) {
        InvocationContext invocationContext = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(AiToolInvocationPolicyTest.class.getName())
                .methodName("authorize")
                .methodArguments(List.of(TASK_ID))
                .userMessage(UserMessage.from("test"))
                .chatMemoryId(APP_ID)
                .timestampNow()
                .build();
        return BeforeToolExecution.builder()
                .request(request)
                .invocationContext(invocationContext)
                .build();
    }

    private ToolExecutionRequest request(String toolName, String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name(toolName)
                .arguments(arguments)
                .build();
    }

    private static class TestTool extends BaseTool {
        private final String name;
        private final ToolRiskLevel riskLevel;

        private TestTool(String name, ToolRiskLevel riskLevel) {
            this.name = name;
            this.riskLevel = riskLevel;
        }

        @Override
        public String getToolName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public ToolRiskLevel getRiskLevel() {
            return riskLevel;
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            return name;
        }
    }

    private static final class GatedTestTool extends TestTool implements ApprovalGatedTool {
        private final AtomicInteger authorizations;
        private final GenerationApprovalRequiredException approvalRequired;

        private GatedTestTool(String name,
                              AtomicInteger authorizations,
                              GenerationApprovalRequiredException approvalRequired) {
            super(name, ToolRiskLevel.DESTRUCTIVE);
            this.authorizations = authorizations;
            this.approvalRequired = approvalRequired;
        }

        @Override
        public void authorizeInvocation(ToolExecutionRequest request, Long appId) {
            authorizations.incrementAndGet();
            if (approvalRequired != null) {
                throw approvalRequired;
            }
        }
    }
}
