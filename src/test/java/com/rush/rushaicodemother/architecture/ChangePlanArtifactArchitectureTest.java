package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止变更计划的权限字段重新散落到生产、恢复和工具执行链。 */
class ChangePlanArtifactArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void producersMustPublishTypedChangePlanArtifact() throws Exception {
        List<Path> producers = List.of(
                Path.of("agent", "CodeAgentNode.java"),
                Path.of("agent", "NoPlanningAgentNode.java"),
                Path.of("create", "CreatePostGenerationValidationService.java")
        );

        for (Path producer : producers) {
            assertThat(Files.readString(SOURCE_ROOT.resolve(producer)))
                    .as("%s 必须通过 ChangePlan 发布持久制品", producer)
                    .contains(".toArtifact(")
                    .doesNotContain("\"change_plan\"");
        }
    }

    @Test
    void permissionCriticalConsumersMustRestoreTypedChangePlan() throws Exception {
        List<Path> consumers = List.of(
                Path.of("agent", "ReviewAgentNode.java"),
                Path.of("heavy", "HeavyGenerationPreparationService.java"),
                Path.of("patch", "GenerationPatchApplyService.java"),
                Path.of("patch", "GenerationPatchResultService.java"),
                Path.of("snapshot", "GenerationRollbackRestoreService.java")
        );

        for (Path consumer : consumers) {
            String source = Files.readString(SOURCE_ROOT.resolve(consumer));
            assertThat(source)
                    .as("%s 必须严格恢复 ChangePlan", consumer)
                    .doesNotContain("ChangePlan.fromPayload(", "\"change_plan\"");
            assertThat(source.contains("ChangePlan.fromArtifact(")
                    || source.contains("ChangePlan::fromArtifact"))
                    .as("%s 必须通过 ChangePlan.fromArtifact 恢复", consumer)
                    .isTrue();
        }
    }

    @Test
    void orchestrationMustUseChangePlanKeyOwner() throws Exception {
        List<Path> consumers = List.of(
                Path.of("AgentGenerationOrchestrator.java"),
                Path.of("heavy", "HeavyGenerationFailureRecoveryService.java"),
                Path.of("heavy", "HeavyGenerationFinalizationService.java"),
                Path.of("heavy", "HeavyGenerationSessionCompletionService.java")
        );

        for (Path consumer : consumers) {
            assertThat(Files.readString(SOURCE_ROOT.resolve(consumer)))
                    .as("%s 必须复用 ChangePlan.KEY", consumer)
                    .contains("ChangePlan.KEY")
                    .doesNotContain("\"change_plan\"");
        }
    }
}
