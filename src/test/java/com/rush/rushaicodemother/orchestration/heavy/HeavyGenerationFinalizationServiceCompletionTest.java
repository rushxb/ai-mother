package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionPolicy;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HeavyGenerationFinalizationServiceCompletionTest {

    @Test
    void legacyBackendTaskMustUseBuildCompletionGraph() {
        GenerationCompletionPolicy completionPolicy = mock(GenerationCompletionPolicy.class);
        HeavyGenerationFinalizationService service = new HeavyGenerationFinalizationService(
                null, null, null, null, null, null, completionPolicy);
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.BACKEND_PROJECT,
                CodeGenTypeEnum.BACKEND_PROJECT,
                false,
                "build",
                "测试任务",
                List.of(),
                new LinkedHashMap<>(),
                null,
                Map.of(),
                "heavy-finalization-test"
        );
        GenerationSession session = new GenerationSession(preparation);
        ArgumentCaptor<GenerationExecutionPlan.ValidationGraph> graphCaptor =
                ArgumentCaptor.forClass(GenerationExecutionPlan.ValidationGraph.class);

        service.requireCompletionEvidence(preparation, session);

        verify(completionPolicy).requireCompletable(
                eq(session), graphCaptor.capture(), any(GenerationCompletionEvidenceSet.class));
        assertEquals(ExpectedValidationLevel.BUILD, graphCaptor.getValue().level());
    }
}
