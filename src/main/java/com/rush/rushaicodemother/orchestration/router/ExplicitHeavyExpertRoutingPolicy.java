package com.rush.rushaicodemother.orchestration.router;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Order(30)
public class ExplicitHeavyExpertRoutingPolicy implements GenerationRoutingPolicy {

    private static final List<String> HEAVY_EXPERT_KEYWORDS = List.of(
            "完整重构", "彻底重构", "重构整个", "全部重写", "从头重写", "推倒重来",
            "重新生成整个项目", "专家模式", "深度重构", "更换技术栈", "换框架"
    );

    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        if (!signal.existingWorkspace() || !signal.containsAny(HEAVY_EXPERT_KEYWORDS)) {
            return Optional.empty();
        }
        return Optional.of(GenerationModeDecision.of(
                GenerationMode.HEAVY_EXPERT,
                0.9,
                "User explicitly requested full refactor or expert handling",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT,
                GenerationRoutingDecisionCode.EXPLICIT_HEAVY_EXPERT
        ));
    }
}
