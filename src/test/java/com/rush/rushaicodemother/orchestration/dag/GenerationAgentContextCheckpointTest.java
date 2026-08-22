package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationSpecificationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationAgentContextCheckpointTest {

    @Test
    void contradictoryPersistedQualityGateMustBeRejected() {
        GenerationOrchestrationTask task = taskWithSpecification();
        task.setTaskId("task-corrupted-quality-gate");
        Map<String, Object> corruptedPayload = new LinkedHashMap<>(validGateArtifact(task).payload());
        corruptedPayload.put("level", "blocker");
        corruptedPayload.put("blockers", List.of("检测到高危依赖"));
        task.getArtifacts().put("quality_gate", GenerationArtifact.of(
                "quality_gate",
                "Review",
                "质量门禁",
                corruptedPayload
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationAgentContext(request(), task, true)
        );
    }

    @Test
    void stringBooleanQualityGateMustBeRejected() {
        GenerationOrchestrationTask task = taskWithSpecification();
        task.setTaskId("task-string-quality-gate");
        Map<String, Object> corruptedPayload = new LinkedHashMap<>(validGateArtifact(task).payload());
        corruptedPayload.put("passed", "true");
        task.getArtifacts().put("quality_gate", GenerationArtifact.of(
                "quality_gate",
                "Review",
                "质量门禁",
                corruptedPayload
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationAgentContext(request(), task, true)
        );
    }

    @Test
    void qualityGateForDifferentReviewedInputsMustBeRejected() {
        GenerationOrchestrationTask task = taskWithSpecification("按旧需求生成商城首页");
        task.setTaskId("task-stale-quality-gate");
        GenerationArtifact staleGate = validGateArtifact(task);
        task.getArtifacts().put(
                GenerationSpecificationArtifact.KEY,
                specification("按当前需求生成管理后台")
        );
        task.getArtifacts().put("quality_gate", staleGate);

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

    private GenerationOrchestrationTask taskWithSpecification() {
        return taskWithSpecification("按当前需求生成管理后台");
    }

    private GenerationOrchestrationTask taskWithSpecification(String enhancedPrompt) {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.getArtifacts().put(
                GenerationSpecificationArtifact.KEY,
                specification(enhancedPrompt)
        );
        return task;
    }

    private GenerationArtifact specification(String enhancedPrompt) {
        return GenerationSpecificationArtifact.execution(
                enhancedPrompt,
                true,
                true,
                Map.of()
        ).toArtifact("Code", "当前生成规范");
    }

    private GenerationArtifact validGateArtifact(GenerationOrchestrationTask task) {
        QualityGateArtifact.ReviewSubject subject = QualityGateArtifact.reviewSubject(
                CodeGenTypeEnum.VUE_PROJECT,
                task.getArtifacts()
        );
        return QualityGateArtifact.fromResult(
                QualityGateResult.passed(List.of(), List.of("生成规范已构建")),
                subject,
                Map.of()
        ).toArtifact("Review", "质量门禁");
    }
}
