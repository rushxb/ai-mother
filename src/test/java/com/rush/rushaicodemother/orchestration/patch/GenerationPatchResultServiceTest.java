package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPatchResultServiceTest {

    private final GenerationPatchResultService service = new GenerationPatchResultService();

    @Test
    void shouldMarkAppliedWhenActualDiffMatchesChangePlan() {
        PatchResult result = service.evaluate(
                1L,
                "task-1",
                changePlan(List.of("src/New.vue"), List.of("src/App.vue"), List.of("src/Old.vue")),
                diffSummary(List.of("src/New.vue"), List.of("src/App.vue"), List.of("src/Old.vue"))
        );

        assertEquals("applied", result.status());
        assertEquals(3, result.matchedFileCount());
        assertTrue(result.unplannedFiles().isEmpty());
        assertTrue(result.missingPlannedFiles().isEmpty());
    }

    @Test
    void shouldMarkDriftedWhenActualDiffLeavesPlanBoundary() {
        PatchResult result = service.evaluate(
                2L,
                "task-2",
                changePlan(List.of(), List.of("src/App.vue"), List.of()),
                diffSummary(List.of("src/New.vue"), List.of("src/App.vue"), List.of())
        );

        assertEquals("drifted", result.status());
        assertEquals(List.of("add:src/New.vue"), result.unplannedFiles());
        assertTrue(result.missingPlannedFiles().isEmpty());
        assertEquals("actual_diff_outside_change_plan", result.reason());
    }

    @Test
    void shouldReportPlannedFilesThatWereNotChanged() {
        PatchResult result = service.evaluate(
                3L,
                "task-3",
                changePlan(List.of(), List.of("src/App.vue", "src/Form.vue"), List.of()),
                diffSummary(List.of(), List.of("src/App.vue"), List.of())
        );

        assertEquals("drifted", result.status());
        assertEquals(List.of("modify:src/Form.vue"), result.missingPlannedFiles());
    }

    @Test
    void shouldSkipWhenDiffSummaryWasSkipped() {
        PatchResult result = service.evaluate(
                4L,
                "task-4",
                changePlan(List.of(), List.of("src/App.vue"), List.of()),
                GenerationArtifact.of("diff_summary", "test", "diff", Map.of("status", "skipped"))
        );

        assertEquals("skipped", result.status());
        assertEquals("diff_summary_not_created", result.reason());
    }

    private GenerationArtifact changePlan(List<String> addFiles, List<String> modifyFiles, List<String> deleteFiles) {
        return GenerationArtifact.of("change_plan", "test", "plan", Map.of(
                "schemaVersion", "v1",
                "changeScope", "single_module_patch",
                "addFiles", addFiles,
                "modifyFiles", modifyFiles,
                "deleteFiles", deleteFiles,
                "impactedModules", List.of("core"),
                "validationLevel", "review_only",
                "rollbackStrategy", "manual_retry_without_snapshot"
        ));
    }

    private GenerationArtifact diffSummary(List<String> addedFiles, List<String> modifiedFiles, List<String> deletedFiles) {
        return GenerationArtifact.of("diff_summary", "test", "diff", Map.of(
                "status", "created",
                "addedFiles", addedFiles,
                "modifiedFiles", modifiedFiles,
                "deletedFiles", deletedFiles
        ));
    }
}
