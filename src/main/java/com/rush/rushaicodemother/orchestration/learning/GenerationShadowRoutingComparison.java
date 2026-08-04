package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;

import java.util.Objects;

/** 一次 Champion 与 Challenger 影子路由对比结果。 */
public record GenerationShadowRoutingComparison(
        IntentProfile intentProfile,
        GenerationModeDecision champion,
        GenerationModeDecision challenger
) {

    public GenerationShadowRoutingComparison {
        Objects.requireNonNull(intentProfile, "意图画像不能为空");
        Objects.requireNonNull(champion, "主路由决策不能为空");
        Objects.requireNonNull(challenger, "候选路由决策不能为空");
    }

    /** 只比较实际路由模式，决策码差异由独立指标标签承担归因。 */
    public boolean agreement() {
        return champion.mode() == challenger.mode();
    }
}