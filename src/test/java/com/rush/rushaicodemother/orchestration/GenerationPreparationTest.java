package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPreparationTest {

    @Test
    void backendProjectMustNotBypassBuildValidationFromAnOldArtifact() {
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.BACKEND_PROJECT,
                true,
                AppConstant.GENERATING_STAGE_CREATE,
                "创建后端应用",
                List.of(),
                Map.of("generation_spec", GenerationArtifact.of(
                        "generation_spec",
                        "Planner",
                        "生成规范",
                        Map.of("requiresBuild", false)
                )),
                QualityGateResult.passed(List.of(), List.of()),
                Map.of(),
                "task-backend-build-gate"
        );

        assertTrue(preparation.requiresBuildValidation());
    }
}
