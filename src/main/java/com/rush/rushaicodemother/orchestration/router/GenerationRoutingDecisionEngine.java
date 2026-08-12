package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.orchestration.learning.IntentProfileRoutingDecisionEngine;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 应用有序路由策略并拥有确定性回退决策。 */
@Component
public class GenerationRoutingDecisionEngine {

    private final List<GenerationRoutingPolicy> policies;

    public GenerationRoutingDecisionEngine(List<GenerationRoutingPolicy> policies) {
        this.policies = policies == null ? List.of() : List.copyOf(policies);
    }

    /**
 * 返回默认{@code Engine}。
 *
 * @return 生成路由决策
 */
    public static GenerationRoutingDecisionEngine defaultEngine() {
        return new GenerationRoutingDecisionEngine(List.of(
                new IntentProfileRoutingPolicy(new IntentProfileRoutingDecisionEngine()),
                new CreateHeavyExpertRoutingPolicy(),
                new CreateFirstRoutingPolicy(),
                new ExplicitHeavyExpertRoutingPolicy(),
                new AgentEditRoutingPolicy(),
                new LightweightEditRoutingPolicy()
        ));
    }

    /**
 * 根据输入信号确定生成路由决策。
 *
 * @param signal 输入信号
 * @return 生成路由决策
 */
    public GenerationModeDecision decide(GenerationRoutingSignal signal) {
        Objects.requireNonNull(signal, "生成路由信号不能为空");
        return policies.stream()
                .map(policy -> policy.decide(signal))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .orElseGet(this::defaultExistingWorkspaceDecision);
    }

    private GenerationModeDecision defaultExistingWorkspaceDecision() {
        return GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.62,
                "现有工作区未命中轻量路由信号，采用具备代码理解能力的智能体编辑模式",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                GenerationRoutingDecisionCode.DEFAULT_AGENT_EDIT
        );
    }
}
