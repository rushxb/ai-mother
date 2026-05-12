package com.yupi.yuaicodemother.orchestration.tool;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationToolExecutionContextServiceTest {

    @Test
    void getContextShouldReturnBoundChangePlan() {
        GenerationToolExecutionContextService service = new GenerationToolExecutionContextService();
        ChangePlan changePlan = new ChangePlan("v1", "feature_update", List.of("src/App.vue"), List.of(), List.of(), List.of(), "review_only", "manual_retry_without_snapshot");

        service.bindChangePlan(1L, "task-1", "patch_first", CodeGenTypeEnum.VUE_PROJECT, changePlan, false, "test");

        assertTrue(service.getContext(1L).isPresent());
        assertFalse(service.getContext(1L).get().allowsBootstrapWrite());
    }

    @Test
    void allowsBootstrapWriteShouldHonorExplicitAllowance() {
        GenerationToolExecutionContext context = new GenerationToolExecutionContext(
                1L, "task-1", "full_generation", CodeGenTypeEnum.VUE_PROJECT, null, false, "bootstrap"
        );

        assertTrue(context.allowsBootstrapWrite());
    }
}
