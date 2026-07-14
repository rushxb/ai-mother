package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchApplyServiceTestFactory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
