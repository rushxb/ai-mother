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

    /**
 * 根据输入信号确定创建{@code First}路由策略。
 *
 * @param signal 输入信号
 * @return 可选的创建{@code First}路由策略；不存在时返回空值
 */
    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        if (!signal.firstGeneration()) {
            return Optional.empty();
        }
        return Optional.of(GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.95,
                "工作区尚不存在，采用模板优先的创建模式以更快生成首次预览",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST
        ));
    }
}
