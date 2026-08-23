package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationOrchestrationRequestTest {

    @Test
    void missingFrozenScenarioMustFailAtRequestBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new GenerationOrchestrationRequest(
                null,
                "调整标题",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                false,
                null,
                null,
                null,
                GenerationPlanningVariant.CURRENT_DAG,
                null
        ));
    }
}
