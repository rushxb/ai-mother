package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.orchestration.intent.IntentProfile;

import java.util.Objects;

/** 一次请求中可复用的意图画像与主路由决策。 */
public record GenerationRouteSelection(
        IntentProfile intentProfile,
        GenerationModeDecision decision
) {

    public GenerationRouteSelection {
        Objects.requireNonNull(intentProfile, "意图画像不能为空");
        Objects.requireNonNull(decision, "主路由决策不能为空");
    }
}
