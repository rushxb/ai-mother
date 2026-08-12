package com.rush.rushaicodemother.orchestration.router;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 创建重型Expert路由决策策略。
 */
@Component
@Order(20)
public class CreateHeavyExpertRoutingPolicy implements GenerationRoutingPolicy {

    private static final List<String> CREATE_HEAVY_EXPERT_KEYWORDS = List.of(
            "微服务", "分布式", "kubernetes", "k8s", "多租户", "高并发",
            "支付系统", "区块链", "训练模型", "自研框架", "复杂工作流"
    );

    /**
 * 根据输入信号确定创建重型{@code Expert}路由策略。
 *
 * @param signal 输入信号
 * @return 可选的创建重型{@code Expert}路由策略；不存在时返回空值
 */
    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        if (!signal.firstGeneration() || !signal.containsAny(CREATE_HEAVY_EXPERT_KEYWORDS)) {
            return Optional.empty();
        }
        return Optional.of(GenerationModeDecision.of(
                GenerationMode.HEAVY_EXPERT,
                0.84,
                "首次生成需求超出当前创建模板覆盖范围，采用专家模式",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT,
                GenerationRoutingDecisionCode.CREATE_TEMPLATE_COVERAGE_GAP
        ));
    }
}
