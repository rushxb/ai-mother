package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.monitor.GenerationShadowRoutingMetricsCollector;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationShadowRoutingServiceTest {

    @Test
    void disabledShadowRoutingMustNotExecuteChallenger() {
        GenerationShadowRoutingProperties properties = new GenerationShadowRoutingProperties();
        AtomicInteger calls = new AtomicInteger();
        GenerationShadowRouteChallenger challenger = profile -> {
            calls.incrementAndGet();
            return champion();
        };
        GenerationShadowRoutingService service = new GenerationShadowRoutingService(
                properties,
                challenger,
                GenerationShadowRoutingMetricsCollector.noOp()
        );

        Optional<GenerationShadowRoutingComparison> comparison = service.evaluate(profile(), champion());

        assertTrue(comparison.isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void enabledShadowRoutingShouldRecordComparisonWithoutChangingChampion() {
        GenerationShadowRoutingProperties properties = new GenerationShadowRoutingProperties();
        properties.setEnabled(true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationModeDecision challengerDecision = GenerationModeDecision.of(
                GenerationMode.LIGHT_EDIT,
                0.9,
                "候选轻量路由",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.FAST,
                GenerationRoutingDecisionCode.INTENT_PROFILE_LIGHT_EDIT
        );
        GenerationShadowRoutingService service = new GenerationShadowRoutingService(
                properties,
                ignored -> challengerDecision,
                new GenerationShadowRoutingMetricsCollector(meterRegistry)
        );
        GenerationModeDecision champion = champion();

        GenerationShadowRoutingComparison comparison = service.evaluate(profile(), champion).orElseThrow();

        assertSame(champion, comparison.champion());
        assertSame(challengerDecision, comparison.challenger());
        assertEquals(GenerationMode.AGENT_EDIT, champion.mode());
        assertEquals(GenerationMode.LIGHT_EDIT, comparison.challenger().mode());
        assertTrue(!comparison.agreement());
        assertEquals(1.0, meterRegistry.get("generation_routing_shadow_decisions_total")
                .tags(
                        "status", "success",
                        "champion_route", "agent_edit",
                        "champion_code", "default_agent_edit",
                        "challenger_route", "lightweight_edit",
                        "challenger_code", "intent_profile_light_edit",
                        "operation_type", "edit",
                        "semantic_complexity", "low",
                        "agreement", "false"
                )
                .counter()
                .count());
        assertTrue(meterRegistry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("敏感提示词")));
    }

    @Test
    void sameRouteWithDifferentDecisionCodesShouldBeTreatedAsAgreement() {
        GenerationModeDecision champion = champion();
        GenerationModeDecision challenger = GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.88,
                "候选智能编辑路由",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                GenerationRoutingDecisionCode.INTENT_PROFILE_AGENT_EDIT
        );

        GenerationShadowRoutingComparison comparison =
                new GenerationShadowRoutingComparison(profile(), champion, challenger);

        assertTrue(comparison.agreement());
    }

    @Test
    void challengerFailureMustNotAffectMainRequest() {
        GenerationShadowRoutingProperties properties = new GenerationShadowRoutingProperties();
        properties.setEnabled(true);
        GenerationShadowRoutingService service = new GenerationShadowRoutingService(
                properties,
                ignored -> {
                    throw new IllegalStateException("影子候选故障");
                },
                GenerationShadowRoutingMetricsCollector.noOp()
        );

        Optional<GenerationShadowRoutingComparison> comparison = assertDoesNotThrow(
                () -> service.evaluate(profile(), champion()));

        assertTrue(comparison.isEmpty());
    }

    private IntentProfile profile() {
        return new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.LOW,
                false,
                false,
                IntentDestructiveRisk.LOW,
                1,
                IntentValidationRisk.LOW,
                0.9
        );
    }

    private GenerationModeDecision champion() {
        return GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.62,
                "现有基线路由",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                GenerationRoutingDecisionCode.DEFAULT_AGENT_EDIT
        );
    }
}