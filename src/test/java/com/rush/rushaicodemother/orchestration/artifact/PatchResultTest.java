package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchResultTest {

    @Test
    void shouldRoundTripValidatedDriftFacts() {
        ChangePlan changePlan = new ChangePlan(
                "v1",
                "single_module_patch",
                List.of(),
                List.of("src/App.vue"),
                List.of(),
                List.of("frontend"),
                "review_only",
                "manual_retry_without_snapshot"
        );
        PatchResult original = PatchResult.created(
                7L,
                "task-7",
                changePlan,
                List.of("src/Unexpected.vue"),
                List.of("src/App.vue"),
                List.of(),
                List.of("add:src/Unexpected.vue"),
                List.of()
        );

        PatchResult restored = PatchResult.fromArtifact(original.toArtifact(), 7L, "task-7");

        assertThat(restored).isEqualTo(original);
        assertThat(restored.drifted()).isTrue();
        assertThat(restored.matchedFileCount()).isEqualTo(1);
    }

    @Test
    void appliedPatchMustNotHideMissingPlannedFiles() {
        PatchResult forgedAppliedResult = new PatchResult(
                "v1",
                "local_diff",
                "applied",
                7L,
                "task-7",
                List.of(),
                List.of("src/App.vue"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                0,
                "",
                null
        );

        assertThatThrownBy(() -> PatchResult.fromArtifact(
                GenerationArtifact.of(PatchResult.KEY, "test", "伪造补丁结果", forgedAppliedResult.toPayload()),
                7L,
                "task-7"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missingPlannedFiles");
    }
}
