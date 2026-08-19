package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiffSummaryTest {

    @Test
    void createdSummaryMustRoundTripThroughTheTypedArtifact() {
        DiffSummary source = DiffSummary.created(
                7L,
                "task-7",
                "D:/workspace/base",
                "D:/workspace/current",
                List.of("src/new.ts"),
                List.of("src/App.vue"),
                List.of("src/old.ts"),
                List.of("src/App.vue | 内容已变更")
        );

        DiffSummary restored = DiffSummary.fromArtifact(source.toArtifact(), 7L, "task-7");

        assertThat(restored.toPayload()).isEqualTo(source.toPayload());
        assertThat(restored.created()).isTrue();
        assertThat(restored.hasChanges()).isTrue();
        assertThat(restored.changedFileCount()).isEqualTo(3);
    }

    @Test
    void summaryFromAnotherTaskMustBeRejected() {
        GenerationArtifact artifact = DiffSummary.created(
                7L,
                "task-foreign",
                "D:/workspace/base",
                "D:/workspace/current",
                List.of("src/App.vue"),
                List.of(),
                List.of(),
                List.of()
        ).toArtifact();

        assertThatThrownBy(() -> DiffSummary.fromArtifact(artifact, 7L, "task-7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void anotherProviderUsingTheSameSchemaMustRemainExtensible() {
        Map<String, Object> payload = new LinkedHashMap<>(DiffSummary.created(
                7L,
                "task-7",
                "D:/workspace/base",
                "D:/workspace/current",
                List.of("src/App.vue"),
                List.of(),
                List.of(),
                List.of()
        ).toPayload());
        payload.put("provider", "container_snapshot");
        GenerationArtifact artifact = GenerationArtifact.of(
                DiffSummary.KEY, "test", "容器差异摘要", payload);

        DiffSummary restored = DiffSummary.fromArtifact(artifact, 7L, "task-7");

        assertThat(restored.provider()).isEqualTo("container_snapshot");
    }

    @Test
    void skippedSummaryMustRejectStaleChangeResults() {
        Map<String, Object> payload = new LinkedHashMap<>(DiffSummary.skipped(
                7L,
                "task-7",
                "D:/workspace/base",
                "D:/workspace/current",
                "snapshot_unavailable"
        ).toPayload());
        payload.put("addedCount", 1);
        payload.put("addedFiles", List.of("src/App.vue"));
        GenerationArtifact artifact = GenerationArtifact.of(
                DiffSummary.KEY, "test", "损坏的差异摘要", payload);

        assertThatThrownBy(() -> DiffSummary.fromArtifact(artifact, 7L, "task-7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skipped");
    }
}
