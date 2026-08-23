package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedValidationCompletionEvidenceFactoryTest {

    @Test
    void incompleteCreateMustNotProduceAnyCompletionEvidence() {
        SlotFillResult partialResult = SlotFillResult.partial(
                "vue-web-admin",
                List.of("table_columns"),
                List.of(PatchOperation.add("src/data/table.ts", "export const columns = []")),
                "仅生成商品表格",
                25,
                List.of("form_modal"));

        GenerationCompletionEvidenceSet evidence =
                ObservedValidationCompletionEvidenceFactory.forCompletedCreate(
                        partialResult, buildObservation());

        assertTrue(evidence.evidence().isEmpty());
        assertFalse(evidence.contains(GenerationCompletionEvidenceType.INTENT_COVERAGE));
    }

    @Test
    void completeCreateMayCombineCoverageMutationAndObservedValidation() {
        SlotFillResult completeResult = SlotFillResult.success(
                "vue-web-admin",
                List.of("table_columns"),
                List.of(PatchOperation.add("src/data/table.ts", "export const columns = []")),
                "商品表格已生成",
                25);

        GenerationCompletionEvidenceSet evidence =
                ObservedValidationCompletionEvidenceFactory.forCompletedCreate(
                        completeResult, buildObservation());

        assertTrue(evidence.contains(GenerationCompletionEvidenceType.INTENT_COVERAGE));
        assertTrue(evidence.contains(GenerationCompletionEvidenceType.WORKSPACE_CHANGE));
        assertTrue(evidence.contains(GenerationCompletionEvidenceType.BUILD_VALIDATION));
    }

    private GenerationValidationObservation buildObservation() {
        return GenerationValidationObservation.passed(
                CodeGenTypeEnum.VUE_PROJECT,
                "create_build_validation",
                Set.of(
                        GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                        GenerationExecutionPlan.ValidationStep.BUILD),
                Map.of());
    }
}
