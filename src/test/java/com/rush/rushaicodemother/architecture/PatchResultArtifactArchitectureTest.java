package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止补丁发布和长期记忆重新绕过强类型落盘事实契约。 */
class PatchResultArtifactArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void finalizationMustPublishAndRenderValidatedPatchResult() throws Exception {
        String source = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "heavy", "HeavyGenerationFinalizationService.java")));

        assertThat(source)
                .contains("patchResult.toArtifact()", "PatchResult.fromArtifact(")
                .doesNotContain("patchResult.payload().get(\"status\")")
                .doesNotContain("\"patch_result\",");
    }

    @Test
    void outcomeMemoryMustRejectRawOrForeignPatchPayloads() throws Exception {
        String source = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "heavy", "HeavyGenerationSessionCompletionService.java")));

        assertThat(source)
                .contains("PatchResult.fromArtifact(")
                .doesNotContain("String.valueOf(patchResult.payload())");
    }
}
