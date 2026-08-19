package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止完成判定、质量指标和提交服务重新分散解释差异摘要字段。 */
class DiffSummaryArtifactArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void criticalConsumersMustCrossTheTypedDiffSummaryInterface() throws Exception {
        assertTypedConsumer(Path.of(
                "attempt", "completion", "HeavyGenerationCompletionEvidenceFactory.java"));
        assertTypedConsumer(Path.of(
                "heavy", "HeavyGenerationSessionCompletionService.java"));
        assertTypedConsumer(Path.of(
                "snapshot", "GenerationCommitService.java"));
        assertTypedConsumer(Path.of(
                "patch", "GenerationPatchResultService.java"));
    }

    @Test
    void finalizationMustPublishAndRenderTheTypedArtifact() throws Exception {
        String finalization = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "heavy", "HeavyGenerationFinalizationService.java")));

        assertThat(finalization)
                .contains("summary.toArtifact()", "DiffSummary.fromArtifact(")
                .doesNotContain("diffSummary.payload().get(")
                .doesNotContain("\"diff_summary\"");
    }

    private void assertTypedConsumer(Path relativePath) throws Exception {
        String source = Files.readString(SOURCE_ROOT.resolve(relativePath));

        assertThat(source)
                .contains("DiffSummary.fromArtifact(")
                .doesNotContain("payload().get(\"addedCount\")")
                .doesNotContain("payload().get(\"modifiedCount\")")
                .doesNotContain("payload().get(\"deletedCount\")");
    }
}
