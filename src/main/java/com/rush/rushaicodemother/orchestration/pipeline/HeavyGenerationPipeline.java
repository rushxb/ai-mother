package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationCoordinator;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 重型生成处理流水线。
 */
@Order(100)
@Component
@RequiredArgsConstructor
public class HeavyGenerationPipeline implements GenerationPipeline {

    public static final String ROUTE = GenerationRoute.HEAVY_GENERATION;

    private final HeavyGenerationCoordinator heavyGenerationCoordinator;

    @Override
    public String route() {
        return ROUTE;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return request.modeIs(GenerationMode.HEAVY_EXPERT);
    }

    @Override
    public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
        heavyGenerationCoordinator.startManaged(request);
        return GenerationPipelineOutcome.running(route());
    }
}
