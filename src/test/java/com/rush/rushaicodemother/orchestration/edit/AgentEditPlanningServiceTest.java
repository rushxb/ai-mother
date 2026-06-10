package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditOperation;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEditPlanningServiceTest {

    private final AgentEditPlanningService planningService = new AgentEditPlanningService();

    @Test
    void shouldBuildChangePlanFromPatchOperations() {
        AgentEditReadResult readResult = new AgentEditReadResult(
                "contract_change",
                List.of(),
                context("src/App.vue", "<template />"),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "medium"
        );
        AgentEditUnderstanding understanding = new AgentEditUnderstanding(
                "summary",
                List.of("src/App.vue"),
                List.of(),
                List.of("src"),
                List.of(),
                List.of("App"),
                List.of(),
                "medium"
        );
        List<PatchOperation> operations = List.of(
                PatchOperation.replace("src/App.vue", "old", "new"),
                PatchOperation.add("src/api/user.ts", "export const listUsers = () => []"),
                PatchOperation.delete("src/legacy.ts")
        );

        EditChangePlan plan = planningService.plan(
                readResult,
                understanding,
                CodeGenTypeEnum.VUE_PROJECT,
                new EditResult("done", List.of(), new EditResult.EditValidation(true, "api change")),
                operations
        );

        assertEquals("cross_module_patch", plan.scope());
        assertEquals(List.of("src/App.vue"), plan.modifyFiles());
        assertEquals(List.of("src/api/user.ts"), plan.addFiles());
        assertEquals(List.of("src/legacy.ts"), plan.deleteFiles());
        assertEquals("build_validation", plan.validation());
        assertEquals("snapshot", plan.rollback());
        assertTrue(plan.toArtifactPlan().modifyFiles().contains("src/App.vue"));
    }

    @Test
    void shouldConvertAiEditOperationsToPatchOperations() {
        EditResult editResult = new EditResult(
                "ok",
                List.of(
                        new EditOperation("replace", "src/App.vue", "old", "new", null),
                        new EditOperation("modify", "src/main.ts", null, null, "main"),
                        new EditOperation("add", "src/New.vue", null, null, "new"),
                        new EditOperation("delete", "src/Old.vue", null, null, null),
                        new EditOperation("unsupported", "src/Skip.vue", null, null, null)
                ),
                null
        );

        List<PatchOperation> operations = planningService.convertToPatchOperations(editResult);

        assertEquals(4, operations.size());
        assertEquals(PatchOperation.ACTION_REPLACE, operations.get(0).action());
        assertEquals(PatchOperation.ACTION_MODIFY, operations.get(1).action());
        assertEquals(PatchOperation.ACTION_ADD, operations.get(2).action());
        assertEquals(PatchOperation.ACTION_DELETE, operations.get(3).action());
    }

    private EditContextPackage context(String path, String content) {
        EditFileCandidate candidate = new EditFileCandidate(path, path, "test", 100, "test", List.of());
        return new EditContextPackage(List.of(candidate), Map.of(path, content), content.length(), "");
    }
}
