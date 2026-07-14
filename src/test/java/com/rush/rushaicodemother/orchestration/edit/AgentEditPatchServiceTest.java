package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchApplyServiceTestFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentEditPatchServiceTest {

    private final AgentEditPatchService patchService = new AgentEditPatchService(
            PatchApplyServiceTestFactory.create()
    );

    @Test
    void shouldRejectAgentPatchOutsideChangePlan() throws Exception {
        Path root = Path.of("target", "test-workspaces", "agent-edit-patch", "outside-plan");
        FileUtil.del(root.toFile());
        Files.createDirectories(root.resolve("src"));
        EditChangePlan plan = new EditChangePlan(
                "cross_module_patch",
                List.of("src/App.vue"),
                List.of(),
                List.of(),
                List.of("modify app"),
                "validate_light",
                "medium",
                "snapshot"
        );

        PatchApplyResult result = patchService.apply(1L, "agent-task", root, plan, List.of(
                PatchOperation.add("src/Unexpected.vue", "<template />")
        ));

        assertEquals("rejected", result.status());
        assertEquals("patch_operation_validation_failed", result.reason());
        assertFalse(Files.exists(root.resolve("src/Unexpected.vue")));
    }
}
