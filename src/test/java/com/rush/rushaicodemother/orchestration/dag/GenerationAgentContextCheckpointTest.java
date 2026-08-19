package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationAgentContextCheckpointTest {

    @Test
    void contradictoryPersistedQualityGateMustBeRejected() {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-corrupted-quality-gate");
        task.getArtifacts().put("quality_gate", GenerationArtifact.of(
                "quality_gate",
                "Review",
                "质量门禁",
                Map.of(
                        "passed", true,
                        "level", "blocker",
                        "blockers", List.of("检测到高危依赖"),
                        "warnings", List.of(),
                        "passes", List.of()
                )
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationAgentContext(request(), task, true)
        );
    }

    @Test
    void stringBooleanQualityGateMustBeRejected() {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-string-quality-gate");
        task.getArtifacts().put("quality_gate", GenerationArtifact.of(
                "quality_gate",
                "Review",
                "质量门禁",
                Map.of(
                        "passed", "true",
                        "level", "pass",
                        "blockers", List.of(),
                        "warnings", List.of(),
                        "passes", List.of("生成规范已构建")
                )
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationAgentContext(request(), task, true)
        );
    }

    private GenerationOrchestrationRequest request() {
        return new GenerationOrchestrationRequest(
                null,
                "生成一个后台管理系统",
                CodeGenTypeEnum.VUE_PROJECT,
                "generating",
                false,
                ignored -> CodeGenTypeEnum.VUE_PROJECT,
                ""
        );
    }
}
