package com.rush.rushaicodemother.orchestration.router;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD,
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST
        ));
    }
}
