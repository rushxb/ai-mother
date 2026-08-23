package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.rush.rushaicodemother.orchestration.GenerationOrchestrationTestFixture.frozenRequest;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationAgentContextRequirementsRestoreTest {

    @Test
    void malformedPersistedRequirementMustFailBeforeDagResume() {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.getArtifacts().put("requirements", GenerationArtifact.of(
                "requirements",
                "Planner",
                "需求与目标",
                Map.of(
                        "targetType", CodeGenTypeEnum.VUE_PROJECT.getValue(),
                        "upgradeRequired", "yes"
                )
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationAgentContext(request(), task, true)
        );
    }

    private GenerationOrchestrationRequest request() {
        App app = App.builder()
                .id(1L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        return frozenRequest(app, "继续生成", CodeGenTypeEnum.VUE_PROJECT, "生成中", true);
    }
}
