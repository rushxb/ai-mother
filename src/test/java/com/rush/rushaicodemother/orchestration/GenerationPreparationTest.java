package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void expertValidationFloorMustOverrideReviewOnlyVueArtifact() {
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                AppConstant.GENERATING_STAGE_CREATE,
                "创建前端应用",
                List.of(),
                Map.of("generation_spec", GenerationArtifact.of(
                        "generation_spec",
                        "Planner",
                        "生成规范",
                        Map.of("requiresBuild", false)
                )),
                QualityGateResult.passed(List.of(), List.of()),
                Map.of(),
                "task-vue-validation-floor"
        );

        GenerationPreparation enforced = preparation.enforceValidationFloor(ExpectedValidationLevel.EXPERT);

        assertFalse(preparation.requiresBuildValidation());
        assertTrue(enforced.requiresBuildValidation());
        assertTrue(enforced.artifact("validation_policy").payload().containsValue("EXPERT"));
    }
}
