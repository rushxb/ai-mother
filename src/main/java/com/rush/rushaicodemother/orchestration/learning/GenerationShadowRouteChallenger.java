package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;

/** 影子路由中的候选决策器。 */
@FunctionalInterface
public interface GenerationShadowRouteChallenger {

    GenerationModeDecision decide(IntentProfile intentProfile);
}
