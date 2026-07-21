package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationToolExecutionContextServiceTest {

    @Test
    void getContextShouldReturnBoundChangePlan() {
        GenerationToolExecutionContextService service = new GenerationToolExecutionContextService();
        ChangePlan changePlan = new ChangePlan("v1", "feature_update", List.of("src/App.vue"), List.of(), List.of(), List.of(), "review_only", "manual_retry_without_snapshot");

        service.bindChangePlan(1L, "task-1", "patch_first", CodeGenTypeEnum.VUE_PROJECT, changePlan, false, "test");

        assertTrue(service.getContext(1L).isPresent());
        assertFalse(service.getContext(1L).get().allowsBootstrapWrite());
    }

    @Test
    void allowsBootstrapWriteShouldHonorExplicitAllowance() {
        GenerationToolExecutionContext context = new GenerationToolExecutionContext(
                1L, "task-1", "full_generation", CodeGenTypeEnum.VUE_PROJECT, null, false, "bootstrap"
        );

        assertTrue(context.allowsBootstrapWrite());
    }

    @Test
    void managedChangePlanBindingMustRetainExactWorkspaceAndFence() {
        GenerationToolExecutionContextService service = new GenerationToolExecutionContextService();
        ChangePlan changePlan = new ChangePlan(
                "v1", "repair", List.of(), List.of("src/App.vue"), List.of(), List.of(),
                "build_validation", "manual_retry_without_snapshot");
        GenerationExecutionFence fence = new GenerationExecutionFence("task-1", "worker-a", 4L);
        Path root = Path.of("target/tool-context/task-1").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, root, root, true, root, null, Set.of(), Set.of());

        service.bindChangePlan(
                1L, "task-1", "repair", CodeGenTypeEnum.VUE_PROJECT, changePlan,
                true, "test", workspace, fence);

        GenerationToolExecutionContext context = service.getContext(fence).orElseThrow();
        assertEquals(workspace, context.workspace());
        assertEquals(fence, context.executionFence());
        assertEquals(context, service.withFence(fence, () -> service.getContext(1L).orElseThrow()));
    }

    @Test
    void dispatchPinShouldBeOptionalWhenRouteHasNotPreparedToolPolicy() {
        GenerationToolExecutionContextService service = new GenerationToolExecutionContextService();
        GenerationExecutionFence fence = new GenerationExecutionFence("task-create", "worker-a", 2L);

        assertFalse(service.bindExecutionFenceIfPresent(1L, fence.taskId(), fence));
        assertTrue(service.getContext(1L).isEmpty());
        assertTrue(service.getContext(fence).isEmpty());
    }
}
