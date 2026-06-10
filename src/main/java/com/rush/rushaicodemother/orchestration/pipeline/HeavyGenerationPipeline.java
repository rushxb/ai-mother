package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Order(100)
@Component
@RequiredArgsConstructor
public class HeavyGenerationPipeline implements GenerationPipeline {

    public static final String ROUTE = "heavy_generation";

    private final ObjectProvider<GenerationTaskOrchestrator> orchestratorProvider;

    @Override
    public String route() {
        return ROUTE;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return request.modeIs(GenerationMode.HEAVY_EXPERT);
    }

    @Override
    public Optional<GenerationTaskResult> execute(GenerationPipelineRequest request) {
        return Optional.of(orchestratorProvider.getObject().startHeavyGeneration(request));
    }
}
