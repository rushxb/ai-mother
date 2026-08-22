package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止生成提交的生产、用户事件和失败事件重新绕过强类型制品契约。 */
class GenerationCommitResultArtifactArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void finalizationMustPublishAndRenderValidatedCommitResult() throws Exception {
        String source = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "heavy", "HeavyGenerationFinalizationService.java")));

        assertThat(source)
                .contains("commitResult.toArtifact()", "GenerationCommitResult.fromArtifact(")
                .doesNotContain(
                        "commitResult.payload().get(\"status\")",
                        "commitArtifact.payload().get(\"status\")")
                .doesNotContain("\"generation_commit\",");
    }

    @Test
    void commitProducerAndFailureEventMustUseArtifactContract() throws Exception {
        String commitService = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "snapshot", "GenerationCommitService.java")));
        String failureRecovery = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "heavy", "HeavyGenerationFailureRecoveryService.java")));

        assertThat(commitService)
                .contains("result.toArtifact()")
                .doesNotContain("\"generation_commit\"");
        assertThat(failureRecovery)
                .contains("GenerationCommitResult.KEY")
                .doesNotContain("\"generation_commit\"");
    }
}
