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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildFixAgentNodeTest {

    private final BuildFixAgentNode node = new BuildFixAgentNode();

    @Test
    void shouldConsumeChangePlanContract() {
        GenerationAgentContext context = newContext();
        context.putArtifacts(List.of(
                GenerationArtifact.of("generation_spec", "Code", "生成规范", Map.of(
                        "patchFirst", true,
                        "requiresBuild", true,
                        "validationMode", "build_validation",
                        "generationMode", "patch_first_update"
                )),
                GenerationArtifact.of("change_plan", "Code", "变更计划", Map.of(
                        "schemaVersion", "v1",
                        "changeScope", "cross_module_patch",
                        "addFiles", List.of("src/pages/NewPage.vue"),
                        "modifyFiles", List.of("src/App.vue", "src/router/index.ts"),
                        "deleteFiles", List.of(),
                        "impactedModules", List.of("navigation", "form"),
                        "validationLevel", "build_validation",
                        "rollbackStrategy", "rollback_to_last_stable_snapshot_or_manual_retry"
                ))
        ));

        AgentNodeResult result = node.execute(context);

        assertEquals(Boolean.TRUE, result.data().get("enabled"));
        assertEquals(Boolean.TRUE, result.data().get("rollbackOnFailure"));
        assertEquals(3, result.data().get("fileChangeCount"));
        assertEquals(List.of("navigation", "form"), result.data().get("impactedModules"));
        assertEquals("rollback_to_last_stable_snapshot_or_manual_retry", result.data().get("rollbackStrategy"));
    }

    @Test
    void shouldFallbackWithoutChangePlan() {
        GenerationAgentContext context = newContext();
        context.putArtifacts(List.of(
                GenerationArtifact.of("generation_spec", "Code", "生成规范", Map.of(
                        "patchFirst", false,
                        "requiresBuild", false,
                        "validationMode", "review_only",
                        "generationMode", "full_generation"
                ))
        ));

        AgentNodeResult result = node.execute(context);

        assertEquals(Boolean.FALSE, result.data().get("enabled"));
        assertEquals(Boolean.FALSE, result.data().get("rollbackOnFailure"));
        assertEquals(0, result.data().get("fileChangeCount"));
        assertTrue(((List<?>) result.data().get("impactedModules")).isEmpty());
    }

    @Test
    void malformedGenerationSpecMustNotDisableBuildGate() {
        GenerationAgentContext context = newContext();
        context.putArtifacts(List.of(
                GenerationArtifact.of("generation_spec", "Code", "生成规范", Map.of(
                        "patchFirst", false,
                        "requiresBuild", "true",
                        "validationMode", "build_validation",
                        "generationMode", "full_generation"
                ))
        ));

        assertThrows(IllegalArgumentException.class, () -> node.execute(context));
    }

    private GenerationAgentContext newContext() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "修复构建失败",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                true,
                null,
                null
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-buildfix");
        return new GenerationAgentContext(request, task, true);
    }
}
