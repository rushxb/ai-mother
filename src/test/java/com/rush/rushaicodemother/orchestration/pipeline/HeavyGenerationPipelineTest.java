package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationCoordinator;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HeavyGenerationPipelineTest {

    @Test
    void heavyRouteMustTransferCompletionOwnershipToCoordinator() {
        HeavyGenerationCoordinator coordinator = mock(HeavyGenerationCoordinator.class);
        HeavyGenerationPipeline pipeline = new HeavyGenerationPipeline(coordinator);
        GenerationPipelineRequest request = requestFor(GenerationMode.HEAVY_EXPERT);

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationPipelineDisposition.RUNNING, outcome.disposition());
        assertEquals(GenerationRoute.HEAVY_GENERATION, outcome.route());
        verify(coordinator).startManaged(request);
    }

    @Test
    void pipelineMustOnlySupportHeavyExpertMode() {
        HeavyGenerationPipeline pipeline = new HeavyGenerationPipeline(mock(HeavyGenerationCoordinator.class));

        assertEquals(GenerationRoute.HEAVY_GENERATION, pipeline.route());
        assertTrue(pipeline.supports(requestFor(GenerationMode.HEAVY_EXPERT)));
        assertFalse(pipeline.supports(requestFor(GenerationMode.CREATE)));
        assertFalse(pipeline.supports(requestFor(GenerationMode.LIGHT_EDIT)));
    }

    private GenerationPipelineRequest requestFor(GenerationMode mode) {
        GenerationModeDecision decision = GenerationModeDecision.of(
                mode, 0.9, "pipeline-test", FallbackPolicy.NONE, ExpectedValidationLevel.BUILD);
        return new GenerationPipelineRequest(null, CodeGenTypeEnum.VUE_PROJECT, null, decision);
    }
}
