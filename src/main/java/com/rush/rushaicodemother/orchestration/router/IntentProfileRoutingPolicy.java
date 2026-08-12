package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.learning.IntentProfileRoutingDecisionEngine;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于结构化意图画像选择生产路由。
 *
 * <p>原始提示词只在意图解析阶段作为证据使用；生产策略不再直接读取关键词，
 * 从而保证主路由与影子路由拥有相同的语义输入。</p>
 */
@Component
@Order(10)
public class IntentProfileRoutingPolicy implements GenerationRoutingPolicy {

    private final IntentProfileRoutingDecisionEngine decisionEngine;

    public IntentProfileRoutingPolicy(IntentProfileRoutingDecisionEngine decisionEngine) {
        this.decisionEngine = decisionEngine;
    }

    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        if (signal == null || signal.intentProfile() == null || isUnknown(signal.intentProfile())) {
            return Optional.empty();
        }
        return Optional.of(decisionEngine.decide(signal.intentProfile()));
    }

    private boolean isUnknown(IntentProfile profile) {
        return profile.confidence() <= 0.0
                && profile.ambiguitySignal() != null
                && profile.ambiguitySignal().ambiguous();
    }
}
