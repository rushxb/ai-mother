package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchApplyServiceTestFactory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionGatewayTest {

    private final GenerationToolExecutionContextService contextService = new GenerationToolExecutionContextService();
    private final GenerationPatchApplyService patchApplyService =
            PatchApplyServiceTestFactory.create();
    private final ToolExecutionGateway gateway = new ToolExecutionGateway(
            patchApplyService, contextService,
            new GenerationExecutionContextService(new GenerationRuntimeProperties()));

    @Test
    void shouldSkipToolWriteWithoutBoundChangePlan() throws Exception {
        Path root = Files.createTempDirectory("tool-gateway-no-plan");

        PatchApplyResult result = gateway.applyPatch(
                10L,
                root,
                PatchOperation.add("src/App.vue", "<template />"),
                "tool-write",
                "test"
        );

        assertEquals("skipped", result.status());
        assertEquals("change_plan_missing", result.reason());
    }

    @Test
    void shouldApplyToolWriteThroughBoundChangePlan() throws Exception {
        Path root = Files.createTempDirectory("tool-gateway-plan");
        Files.createDirectories(root.resolve("src"));
        ChangePlan changePlan = new ChangePlan(
                "v1",
                "single_module_patch",
                List.of("src/App.vue"),
                List.of(),
                List.of(),
                List.of("core-app"),
                "review_only",
                "manual_retry_without_snapshot"
        );
        contextService.bindChangePlan(
                11L,
                "task-11",
                "patch_first",
                CodeGenTypeEnum.VUE_PROJECT,
                changePlan,
                false,
                "test"
        );

        PatchApplyResult result = gateway.applyPatch(
                11L,
                root,
                PatchOperation.add("src/App.vue", "<template>ok</template>"),
                "tool-write",
                "test"
        );

        assertEquals("applied", result.status());
        assertTrue(Files.readString(root.resolve("src/App.vue")).contains("ok"));
    }

    @Test
    void shouldReserveOneBudgetUnitPerBatchOperation() throws Exception {
        Path root = Files.createTempDirectory("tool-gateway-batch-budget");
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setMaxToolWrites(2);
        GenerationExecutionContextService executionContexts =
                new GenerationExecutionContextService(properties);
        executionContexts.start("task-12", 12L, 100L);
        contextService.bindChangePlan(
                12L, "task-12", "full_generation", CodeGenTypeEnum.VUE_PROJECT,
                null, true, "test");
        ToolExecutionGateway budgetedGateway = new ToolExecutionGateway(
                patchApplyService, contextService, executionContexts);

        PatchApplyResult result = budgetedGateway.applyPatch(
                12L,
                root,
                List.of(
                        PatchOperation.add("src/App.vue", "app"),
                        PatchOperation.add("src/main.js", "main")
                ),
                "tool-write",
                "test"
        );

        assertEquals("applied", result.status());
        assertEquals(2, executionContexts.getByTaskId("task-12").orElseThrow()
                .used(GenerationBudgetKind.TOOL_WRITE));
        assertEquals(2, executionContexts.getByTaskId("task-12").orElseThrow()
                .successfulWorkspaceMutationCount());
    }

    @Test
    void rejectedPatchMustNotBeRecordedAsSuccessfulWorkspaceMutation() throws Exception {
        Path root = Files.createTempDirectory("tool-gateway-rejected-mutation");
        GenerationExecutionContextService executionContexts =
                new GenerationExecutionContextService(new GenerationRuntimeProperties());
        executionContexts.start("task-14", 14L, 100L);
        contextService.bindChangePlan(
                14L,
                "task-14",
                "patch_first",
                CodeGenTypeEnum.VUE_PROJECT,
                new ChangePlan(
                        "v1",
                        "single_module_patch",
                        List.of("src/App.vue"),
                        List.of(),
                        List.of(),
                        List.of("core-app"),
                        "review_only",
                        "manual_retry_without_snapshot"
                ),
                false,
                "test"
        );
        ToolExecutionGateway budgetedGateway = new ToolExecutionGateway(
                patchApplyService, contextService, executionContexts);

        PatchApplyResult result = budgetedGateway.applyPatch(
                14L,
                root,
                PatchOperation.add("src/Outside.vue", "outside"),
                "tool-write",
                "test"
        );

        assertEquals("rejected", result.status());
        assertEquals(1, executionContexts.getByTaskId("task-14").orElseThrow()
                .used(GenerationBudgetKind.TOOL_WRITE));
        assertEquals(0, executionContexts.getByTaskId("task-14").orElseThrow()
                .successfulWorkspaceMutationCount());
        assertFalse(Files.exists(root.resolve("src/Outside.vue")));
    }

    @Test
    void insufficientBatchBudgetMustNotConsumeOrWritePartially() throws Exception {
        Path root = Files.createTempDirectory("tool-gateway-batch-budget-reject");
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setMaxToolWrites(1);
        GenerationExecutionContextService executionContexts =
                new GenerationExecutionContextService(properties);
        executionContexts.start("task-13", 13L, 100L);
        contextService.bindChangePlan(
                13L, "task-13", "full_generation", CodeGenTypeEnum.VUE_PROJECT,
                null, true, "test");
        ToolExecutionGateway budgetedGateway = new ToolExecutionGateway(
                patchApplyService, contextService, executionContexts);

        assertThrows(GenerationBudgetExceededException.class, () -> budgetedGateway.applyPatch(
                13L,
                root,
                List.of(
                        PatchOperation.add("src/App.vue", "app"),
                        PatchOperation.add("src/main.js", "main")
                ),
                "tool-write",
                "test"
        ));

        assertEquals(0, executionContexts.getByTaskId("task-13").orElseThrow()
                .used(GenerationBudgetKind.TOOL_WRITE));
        assertFalse(Files.exists(root.resolve("src/App.vue")));
        assertFalse(Files.exists(root.resolve("src/main.js")));
    }
}
