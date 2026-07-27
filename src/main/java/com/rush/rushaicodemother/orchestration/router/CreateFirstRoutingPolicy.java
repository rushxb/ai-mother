package com.rush.rushaicodemother.orchestration.router;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 创建First路由决策策略。
 */
@Component
@Order(20)
public class CreateFirstRoutingPolicy implements GenerationRoutingPolicy {

    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        if (!signal.firstGeneration()) {
            return Optional.empty();
        }
        return Optional.of(GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.95,
                "Workspace does not exist; use CREATE template-first mode for faster first preview",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST
        ));
    }
}
