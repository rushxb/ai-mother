package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchApplyResultTest {

    @Test
    void effectiveChangedPathsMustProjectOperationLabelsWithoutLeakingSchemaParsing() {
        PatchApplyResult result = PatchApplyResult.applied(
                1L,
                "task-1",
                "project",
                4,
                List.of(
                        "add:src/App.vue",
                        "replace:src/App.vue",
                        "modify:src/main.js",
                        "legacy/path.txt"
                )
        );

        assertEquals(
                List.of("src/App.vue", "src/main.js", "legacy/path.txt"),
                result.effectiveChangedPaths()
        );
    }

    @Test
    void malformedAppliedOperationLabelMustFailClosed() {
        PatchApplyResult result = PatchApplyResult.applied(
                1L,
                "task-1",
                "project",
                2,
                List.of("add:src/App.vue", "src/ambiguous:path.ts")
        );

        assertTrue(result.effectiveChangedPaths().isEmpty());
        assertThrows(IllegalStateException.class, result::requireEffectiveChangedPaths,
                "执行计数非零但路径投影损坏时不能被上层误判为 no-op");
    }
}
