package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.orchestration.pipeline.HeavyGenerationPipeline;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;

public enum GenerationMode {

    CREATE(GenerationRoute.CREATE),
    LIGHT_EDIT(GenerationRoute.LIGHTWEIGHT_EDIT),
    AGENT_EDIT(GenerationRoute.AGENT_EDIT),
    HEAVY_EXPERT(HeavyGenerationPipeline.ROUTE);

    private final String route;

    GenerationMode(String route) {
        this.route = route;
    }

    public String route() {
        return route;
    }
}
