package com.yupi.yuaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangePlanTest {

    @Test
    void shouldNormalizePathsAndRoundTripPayload() {
        ChangePlan changePlan = ChangePlan.fromPayload(Map.of(
                "schemaVersion", "v1",
                "changeScope", "single_module_patch",
                "addFiles", List.of("src\\pages\\NewPage.vue", "../hack.js", " "),
                "modifyFiles", List.of("src\\App.vue", "src/App.vue"),
                "deleteFiles", List.of("/etc/passwd", "src\\legacy\\Old.ts"),
                "impactedModules", List.of(" form ", "form", ""),
                "validationLevel", "build_validation",
                "rollbackStrategy", "rollback_to_last_stable_snapshot_or_manual_retry"
        ));

        assertEquals(List.of("src/pages/NewPage.vue"), changePlan.addFiles());
        assertEquals(List.of("src/App.vue"), changePlan.modifyFiles());
        assertEquals(List.of("src/legacy/Old.ts"), changePlan.deleteFiles());
        assertEquals(List.of("form"), changePlan.impactedModules());
        assertEquals(changePlan.toPayload(), ChangePlan.fromPayload(changePlan.toPayload()).toPayload());
    }

    @Test
    void shouldValidatePatchFirstContract() {
        ChangePlan invalidPlan = new ChangePlan(
                "v2",
                "single_module_patch",
                List.of(),
                List.of(),
                List.of(),
                List.of("form"),
                "unknown",
                "manual_retry_without_snapshot"
        );

        List<String> blockers = invalidPlan.validateForPatchFirst(true, "build_validation");

        assertFalse(blockers.isEmpty());
        assertTrue(blockers.stream().anyMatch(message -> message.contains("schemaVersion")));
        assertTrue(blockers.stream().anyMatch(message -> message.contains("validationLevel")));
        assertTrue(blockers.stream().anyMatch(message -> message.contains("文件范围")));
        assertTrue(blockers.stream().anyMatch(message -> message.contains("稳定版本")));
    }
}
