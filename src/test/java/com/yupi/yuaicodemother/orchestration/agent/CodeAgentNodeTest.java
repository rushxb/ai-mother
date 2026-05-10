package com.yupi.yuaicodemother.orchestration.agent;

import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrationRequest;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAgentNodeTest {

    private final CodeAgentNode node = new CodeAgentNode();

    @Test
    void shouldBuildChangePlanAndAvoidNullProjectContext() {
        GenerationAgentContext context = newContext(true);
        context.putArtifacts(List.of(
                GenerationArtifact.of("requirements", "Planner", "需求与目标", Map.of(
                        "patchFirst", true,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "patch_first_update",
                        "goals", List.of("复用已有页面")
                )),
                GenerationArtifact.of("architecture_plan", "Architect", "架构规划", Map.of(
                        "modules", List.of("navigation", "form")
                )),
                GenerationArtifact.of("context_summary", "Context", "项目上下文", Map.of(
                        "selectedFiles", List.of("src/router/index.ts", "src/components/UserForm.vue"),
                        "projectContext", "",
                        "intent", "navigation",
                        "contextMode", "intent_selected_files"
                ))
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
        assertFalse(String.valueOf(specArtifact.payload().get("enhancedPrompt")).contains("null"));
        assertEquals("cross_module_patch", result.data().get("changeScope"));
        assertNotNull(context.getArtifact("requirements").orElse(null));
    }

    private GenerationAgentContext newContext(boolean hasGeneratedCode) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "优化登录页与表单交互",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                hasGeneratedCode,
                null,
                null
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-1");
        return new GenerationAgentContext(request, task, true);
    }
}
