package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止回滚点的 key、字段解析和任务身份校验重新散落到生产链。 */
class RollbackPointArtifactArchitectureTest {

    private static final Path ORCHESTRATION_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void producerAndBehaviorConsumersMustCrossRollbackPointInterface() throws Exception {
        Path producer = ORCHESTRATION_SOURCE_ROOT.resolve(Path.of(
                "snapshot", "GenerationRollbackPointService.java"));
        List<Path> consumers = List.of(
                ORCHESTRATION_SOURCE_ROOT.resolve("AgentGenerationOrchestrator.java"),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of(
                        "snapshot", "GenerationRollbackRestoreService.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of(
                        "snapshot", "GenerationDiffSummaryService.java"))
        );

        assertThat(Files.readString(producer)).contains("rollbackPoint.toArtifact()");
        for (Path consumer : consumers) {
            assertThat(Files.readString(consumer))
                    .as("%s 必须通过强类型回滚点 interface 恢复事实", consumer)
                    .contains("RollbackPoint.fromArtifact");
        }
    }

    @Test
    void rollbackPointKeyMustHaveSingleProductionOwner() throws Exception {
        Path owner = ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("artifact", "RollbackPoint.java"));
        try (var sources = Files.walk(ORCHESTRATION_SOURCE_ROOT)) {
            List<Path> leakedSources = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.equals(owner))
                    .filter(path -> readSource(path).contains("\"rollback_point\""))
                    .toList();

            assertThat(leakedSources)
                    .as("rollback_point 裸 key 只能由 RollbackPoint module 拥有")
                    .isEmpty();
        }
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("读取生产源码失败: " + path, exception);
        }
    }
}
