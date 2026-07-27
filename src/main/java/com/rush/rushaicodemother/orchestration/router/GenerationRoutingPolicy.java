package com.rush.rushaicodemother.orchestration.router;

import java.util.Optional;

/** 生产路由信号的策略接口。 */
public interface GenerationRoutingPolicy {

    Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal);
}
