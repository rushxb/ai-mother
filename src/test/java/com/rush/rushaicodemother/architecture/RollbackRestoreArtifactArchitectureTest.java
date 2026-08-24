package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止回滚恢复结果重新退化为生产者与失败处理各自解释的动态 Map。 */
class RollbackRestoreArtifactArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void producerAndFailureProjectionMustCrossTheTypedArtifactInterface() throws Exception {
        String producer = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "snapshot", "GenerationRollbackRestoreService.java")));
        String consumer = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "heavy", "HeavyGenerationFailureRecoveryService.java")));

        assertThat(producer)
                .contains("restore.toArtifact()")
                .doesNotContain("GenerationArtifact.of(\"rollback_restore\"");
        assertThat(consumer)
                .contains("RollbackRestore.KEY", "RollbackRestore.fromArtifact(")
                .doesNotContain("payload().get(\"status\")", "artifact(\"rollback_restore\")");
    }
}
