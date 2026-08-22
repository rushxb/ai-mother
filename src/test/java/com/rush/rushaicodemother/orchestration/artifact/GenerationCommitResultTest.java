package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationCommitResultTest {

    @Test
    void shouldRoundTripValidatedCommitFacts() {
        GenerationCommitResult original = GenerationCommitResult.committed(
                7L,
                "task-7",
                "/project",
                "1234567890abcdef",
                "main",
                List.of("src/App.vue")
        );

        GenerationCommitResult restored = GenerationCommitResult.fromArtifact(
                original.toArtifact(),
                7L,
                "task-7"
        );

        assertThat(restored).isEqualTo(original);
        assertThat(restored.committed()).isTrue();
    }

    @Test
    void committedStatusMustNotHideForgedFileCount() {
        GenerationCommitResult original = GenerationCommitResult.committed(
                7L,
                "task-7",
                "/project",
                "1234567890abcdef",
                "main",
                List.of("src/App.vue")
        );
        Map<String, Object> forgedPayload = new LinkedHashMap<>(original.toPayload());
        forgedPayload.put("committedFileCount", 2);
        GenerationArtifact forgedArtifact = GenerationArtifact.of(
                GenerationCommitResult.KEY,
                "test",
                "伪造生成提交结果",
                forgedPayload
        );

        assertThatThrownBy(() -> GenerationCommitResult.fromArtifact(
                forgedArtifact,
                7L,
                "task-7"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("committedFileCount");
    }
}
