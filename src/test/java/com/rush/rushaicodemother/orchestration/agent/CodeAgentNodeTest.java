package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.ApiContractArtifact;
import com.rush.rushaicodemother.orchestration.artifact.ContextSummaryArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.rush.rushaicodemother.orchestration.GenerationOrchestrationTestFixture.frozenRequest;
import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.codeAgentNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAgentNodeTest {

    private final CodeAgentNode node = codeAgentNode();

    @Test
    void shouldBuildChangePlanAndAvoidNullProjectContext() {
        GenerationAgentContext context = newContext(true);
        context.putArtifacts(List.of(
                GenerationArtifact.of("requirements", "Planner", "需求与目标", Map.of(
                        "patchFirst", true,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "patch_first_update",
                        "goals", List.of("复用已有页面"),
                        "skills", List.of(Map.of(
                                "id", "vue-admin-dashboard",
                                "title", "Vue Admin Dashboard",
                                "modules", List.of("dashboard", "navigation"),
                                "implementationHints", List.of("复用布局骨架"),
                                "validationHints", List.of("验证菜单跳转"),
                                "promptInstructions", "- 页面、路由和菜单要一起改。",
                                "databaseRequired", false
                        ))
                )),
                GenerationArtifact.of("architecture_plan", "Architect", "架构规划", Map.of(
                        "modules", List.of("navigation", "form")
                )),
                contextSummary(
                        List.of("src/router/index.ts", "src/components/UserForm.vue"),
                        List.of(Map.of(
                                "id", "form-settings",
                                "title", "表单 / 设置页",
                                "modules", List.of("form", "settings"),
                                "implementationSteps", List.of("复用表单结构", "补齐提交状态"),
                                "validationHints", List.of("验证必填校验"),
                                "databaseRequired", false
                        )),
                        List.of(Map.of(
                                "id", "vue-admin-dashboard",
                                "title", "Vue Admin Dashboard",
                                "modules", List.of("dashboard", "navigation"),
                                "implementationHints", List.of("复用布局骨架"),
                                "validationHints", List.of("验证菜单跳转"),
                                "promptInstructions", "- 页面、路由和菜单要一起改。",
                                "databaseRequired", false
                        ))
                )
        ));

        AgentNodeResult result = node.execute(context);
        GenerationArtifact changePlanArtifact = result.artifacts().stream()
                .filter(artifact -> "change_plan".equals(artifact.key()))
                .findFirst()
                .orElseThrow();
        GenerationArtifact specArtifact = result.artifacts().stream()
                .filter(artifact -> "generation_spec".equals(artifact.key()))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        List<String> modifyFiles = (List<String>) changePlanArtifact.payload().get("modifyFiles");
        @SuppressWarnings("unchecked")
        Map<String, Object> changePlan = (Map<String, Object>) specArtifact.payload().get("changePlan");

        assertEquals(List.of("src/router/index.ts", "src/components/UserForm.vue"), modifyFiles);
        assertEquals(changePlanArtifact.payload(), changePlan);
        assertTrue(String.valueOf(specArtifact.payload().get("enhancedPrompt")).contains("【ChangePlan】"));
        assertTrue(String.valueOf(specArtifact.payload().get("enhancedPrompt")).contains("【Skill】"));
        assertTrue(String.valueOf(specArtifact.payload().get("enhancedPrompt")).contains("Vue Admin Dashboard"));
        assertTrue(String.valueOf(specArtifact.payload().get("enhancedPrompt")).contains("【Recipe】"));
        assertTrue(String.valueOf(specArtifact.payload().get("enhancedPrompt")).contains("form-settings"));
        assertFalse(String.valueOf(specArtifact.payload().get("enhancedPrompt")).contains("null"));
        assertEquals("cross_module_patch", result.data().get("changeScope"));
        assertNotNull(context.getArtifact("requirements").orElse(null));
    }

    @Test
    void malformedRecoveredTemplateArtifactMustFailBeforeBuildingGenerationPrompt() {
        GenerationAgentContext context = newContext(false);
        context.putArtifacts(List.of(
                GenerationArtifact.of("requirements", "Planner", "需求与目标", Map.of(
                        "patchFirst", false,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "full_generation",
                        "goals", List.of("生成完整工程")
                )),
                GenerationArtifact.of("architecture_plan", "Architect", "架构规划", Map.of(
                        "modules", List.of("app")
                )),
                contextSummary(List.of(), List.of(), List.of()),
                GenerationArtifact.of("template_bootstrap", "Template", "项目模板", Map.of(
                        "bootstrapped", true,
                        "templateId", "vue-web-basic",
                        "projectPath", "target/workspaces/1",
                        "fileCount", "many",
                        "targetType", CodeGenTypeEnum.VUE_PROJECT.getValue(),
                        "reason", ""
                ))
        ));

        assertThrows(IllegalArgumentException.class, () -> node.execute(context));
    }

    @Test
    void malformedRecoveredRequirementsMustFailBeforeBuildingGenerationPrompt() {
        GenerationAgentContext context = newContext(false);
        context.putArtifacts(List.of(
                GenerationArtifact.of("requirements", "Planner", "需求与目标", Map.of(
                        "goals", List.of(1)
                )),
                GenerationArtifact.of("architecture_plan", "Architect", "架构规划", Map.of(
                        "modules", List.of("app")
                ))
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> node.execute(context)
        );

        assertTrue(exception.getMessage().contains("goals"));
    }

    @Test
    void recoveredArchitecturePlanForAnotherProjectTypeMustFailClosed() {
        GenerationAgentContext context = newContext(false);
        context.putArtifacts(List.of(
                GenerationArtifact.of("requirements", "Planner", "需求与目标", Map.of(
                        "patchFirst", false,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "full_generation",
                        "goals", List.of("生成 Vue 应用")
                )),
                GenerationArtifact.of("architecture_plan", "Architect", "架构规划", Map.of(
                        "modules", List.of("backend-api", "database"),
                        "constraints", List.of("使用 Go HTTP 服务"),
                        "targetType", CodeGenTypeEnum.BACKEND_PROJECT.getValue(),
                        "parallelizable", true
                )),
                contextSummary(List.of(), List.of(), List.of())
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> node.execute(context)
        );

        assertTrue(exception.getMessage().contains("架构计划目标类型"));
    }

    @Test
    void malformedRecoveredContextSummaryMustFailWithDomainDiagnostic() {
        GenerationAgentContext context = newContext(true);
        context.putArtifacts(List.of(
                GenerationArtifact.of("requirements", "Planner", "需求与目标", Map.of(
                        "patchFirst", true,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "patch_first_update",
                        "goals", List.of("保留现有能力")
                )),
                GenerationArtifact.of("architecture_plan", "Architect", "架构规划", Map.of(
                        "modules", List.of("app")
                )),
                GenerationArtifact.of("context_summary", "Context", "项目上下文", Map.of(
                        "intent", "app",
                        "selectedFiles", List.of(Map.of("path", "src/App.vue")),
                        "projectContext", "当前项目摘要"
                ))
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> node.execute(context)
        );

        assertTrue(exception.getMessage().contains("上下文制品字段 selectedFiles"));
    }

    @Test
    void malformedRecoveredApiContractMustNotBeSilentlyOmittedFromPrompt() {
        GenerationAgentContext context = newContext(false);
        context.putArtifacts(List.of(
                GenerationArtifact.of("requirements", "Planner", "需求与目标", Map.of(
                        "patchFirst", false,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "full_generation",
                        "goals", List.of("生成完整工程")
                )),
                GenerationArtifact.of("architecture_plan", "Architect", "架构规划", Map.of(
                        "modules", List.of("app")
                )),
                contextSummary(List.of(), List.of(), List.of()),
                GenerationArtifact.of(ApiContractArtifact.KEY, "Planner", "API 字段契约", Map.of(
                        "status", "ready"
                ))
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> node.execute(context)
        );

        assertTrue(exception.getMessage().contains("API 字段契约"));
    }

    private GenerationAgentContext newContext(boolean hasGeneratedCode) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app, "优化登录页与表单交互", CodeGenTypeEnum.VUE_PROJECT, "update", hasGeneratedCode);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-1");
        return new GenerationAgentContext(request, task, true);
    }

    private GenerationArtifact contextSummary(
            List<String> selectedFiles,
            List<Map<String, Object>> recipes,
            List<Map<String, Object>> skills
    ) {
        return ContextSummaryArtifact.create(
                new ContextSummaryArtifact.RepositoryContext(
                        "navigation",
                        selectedFiles,
                        0,
                        0,
                        List.of(),
                        "intent_selected_files",
                        ""
                ),
                new ContextSummaryArtifact.ContextProtection(
                        "0".repeat(64),
                        false,
                        false,
                        0
                ),
                new ContextSummaryArtifact.AgentGuidance(
                        "",
                        true,
                        recipes,
                        skills
                )
        ).toArtifact();
    }
}
