package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.orchestration.router.GenerationRoutingPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 约束生产路由仅保留遥测前置策略与唯一的结构化画像决策策略。 */
class GenerationRoutingPolicyTopologyArchitectureTest {

    private static final String ROUTER_PACKAGE =
            "com.rush.rushaicodemother.orchestration.router";

    @Test
    void productionRouterMustHaveOneDecisionOwner() throws Exception {
        Set<String> policyImplementations = ProductionClassScanner.load(ROUTER_PACKAGE).stream()
                .filter(type -> GenerationRoutingPolicy.class.isAssignableFrom(type))
                .filter(type -> !type.isInterface())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(policyImplementations)
                .as("生产路由只能由遥测策略前置，并由结构化画像策略拥有最终决策")
                .containsExactlyInAnyOrder(
                        "GenerationTelemetryRoutingPolicy",
                        "IntentProfileRoutingPolicy");
    }
}
