package com.rush.rushaicodemother.orchestration.router;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Explicit重型Expert路由决策策略。
 */
@Component
@Order(30)
public class ExplicitHeavyExpertRoutingPolicy implements GenerationRoutingPolicy {

    private static final List<String> HEAVY_EXPERT_KEYWORDS = List.of(
            "完整重构", "彻底重构", "重构整个", "全部重写", "从头重写", "推倒重来",
            "重新生成整个项目", "专家模式", "深度重构", "更换技术栈", "换框架"
    );

    /**
 * 根据输入信号确定{@code Explicit}重型{@code Expert}路由策略。
 *
 * @param signal 输入信号
 * @return 可选的{@code Explicit}重型{@code Expert}路由策略；不存在时返回空值
 */
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
