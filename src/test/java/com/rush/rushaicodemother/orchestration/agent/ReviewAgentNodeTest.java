package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.rush.rushaicodemother.orchestration.GenerationOrchestrationTestFixture.frozenRequest;
import static com.rush.rushaicodemother.orchestration.agent.GenerationAgentTestFixture.reviewAgentNode;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewAgentNodeTest {

    private final ReviewAgentNode node = reviewAgentNode();

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

    @Test
    void patchFirstMustRejectBootstrapChangePlanRecoveredFromCheckpoint() {
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
                        "changeScope", "project_bootstrap",
                        "addFiles", List.of(),
                        "modifyFiles", List.of(),
                        "deleteFiles", List.of(),
                        "impactedModules", List.of(),
                        "validationLevel", "build_validation",
                        "rollbackStrategy", "rollback_to_last_stable_snapshot_or_manual_retry"
                ))
        ));

        node.execute(context);

        assertFalse(context.getQualityGateResult().passed());
        assertTrue(context.getQualityGateResult().blockers().stream()
                .anyMatch(message -> message.contains("patch-first")
                        && message.contains("project_bootstrap")));
    }

    private GenerationAgentContext newContext() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app, "优化登录页", CodeGenTypeEnum.VUE_PROJECT, "update", true);
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-1");
        return new GenerationAgentContext(request, task, true);
    }
}
