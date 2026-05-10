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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewAgentNodeTest {

    private final ReviewAgentNode node = new ReviewAgentNode();

    @Test
    void shouldBlockPatchFirstWithoutChangePlan() {
        GenerationAgentContext context = newContext();
        context.putArtifacts(List.of(
                GenerationArtifact.of("generation_spec", "Code", "生成规范", Map.of(
                        "enhancedPrompt", "prompt",
                        "patchFirst", true,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "patch_first_update"
                ))
        ));

        AgentNodeResult result = node.execute(context);

        assertFalse(context.getQualityGateResult().passed());
        assertTrue(context.getQualityGateResult().blockers().stream()
                .anyMatch(message -> message.contains("变更计划")));
        assertTrue(Boolean.FALSE.equals(result.data().get("passed")));
    }

    @Test
    void shouldPassWhenChangePlanExists() {
        GenerationAgentContext context = newContext();
        context.putArtifacts(List.of(
                GenerationArtifact.of("generation_spec", "Code", "生成规范", Map.of(
                        "enhancedPrompt", "prompt",
                        "patchFirst", true,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "patch_first_update"
                )),
                GenerationArtifact.of("change_plan", "Code", "变更计划", Map.of(
                        "schemaVersion", "v1",
                        "changeScope", "single_module_patch",
                        "addFiles", List.of(),
                        "modifyFiles", List.of("src/App.vue"),
                        "deleteFiles", List.of(),
                        "impactedModules", List.of("form"),
                        "validationLevel", "build_validation",
                        "rollbackStrategy", "rollback_to_last_stable_snapshot_or_manual_retry"
                ))
        ));

        node.execute(context);

        assertTrue(context.getQualityGateResult().passed());
        assertTrue(context.getQualityGateResult().passes().stream()
                .anyMatch(message -> message.contains("变更计划")));
    }

    @Test
    void shouldBlockInvalidChangePlanContract() {
        GenerationAgentContext context = newContext();
        context.putArtifacts(List.of(
                GenerationArtifact.of("generation_spec", "Code", "生成规范", Map.of(
                        "enhancedPrompt", "prompt",
                        "patchFirst", true,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "patch_first_update"
                )),
                GenerationArtifact.of("change_plan", "Code", "变更计划", Map.of(
                        "schemaVersion", "v1",
                        "changeScope", "single_module_patch",
                        "addFiles", List.of(),
                        "modifyFiles", List.of(),
                        "deleteFiles", List.of(),
                        "impactedModules", List.of("form"),
                        "validationLevel", "review_only",
                        "rollbackStrategy", "manual_retry_without_snapshot"
                ))
        ));

        node.execute(context);

        assertFalse(context.getQualityGateResult().passed());
        assertTrue(context.getQualityGateResult().blockers().stream()
                .anyMatch(message -> message.contains("文件范围")));
        assertTrue(context.getQualityGateResult().blockers().stream()
                .anyMatch(message -> message.contains("validationLevel")));
    }

    private GenerationAgentContext newContext() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "优化登录页",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                true,
                null,
                null
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-1");
        return new GenerationAgentContext(request, task, true);
    }
}
