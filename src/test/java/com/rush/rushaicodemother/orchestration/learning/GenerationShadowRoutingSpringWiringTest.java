package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.monitor.GenerationShadowRoutingMetricsCollector;
import com.rush.rushaicodemother.orchestration.intent.IntentProfileService;
import com.rush.rushaicodemother.orchestration.router.AgentEditRoutingPolicy;
import com.rush.rushaicodemother.orchestration.router.CreateFirstRoutingPolicy;
import com.rush.rushaicodemother.orchestration.router.CreateHeavyExpertRoutingPolicy;
import com.rush.rushaicodemother.orchestration.router.ExplicitHeavyExpertRoutingPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionEngine;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProvider;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetrySnapshot;
import com.rush.rushaicodemother.orchestration.router.LightweightEditRoutingPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationShadowRoutingSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(GenerationRoutingTelemetryProvider.class,
                    () -> (appId, userId) -> GenerationRoutingTelemetrySnapshot.unavailable())
            .withUserConfiguration(
                    GenerationShadowRoutingProperties.class,
                    IntentProfileRoutingDecisionEngine.class,
                    GenerationShadowRoutingMetricsCollector.class,
                    GenerationShadowRoutingService.class,
                    IntentProfileService.class,
                    CreateHeavyExpertRoutingPolicy.class,
                    CreateFirstRoutingPolicy.class,
                    ExplicitHeavyExpertRoutingPolicy.class,
                    AgentEditRoutingPolicy.class,
                    LightweightEditRoutingPolicy.class,
                    GenerationRoutingDecisionEngine.class,
                    GenerationModeRouter.class
            );

    @Test
    void springContextShouldProvideSingleChallengerAndProductionRouter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GenerationShadowRouteChallenger.class);
            assertThat(context).hasSingleBean(GenerationShadowRoutingService.class);
            assertThat(context).hasSingleBean(GenerationModeRouter.class);
        });
    }
}