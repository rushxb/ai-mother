package com.rush.rushaicodemother.orchestration.router;

import java.util.Optional;

/** Strategy interface for production routing signals. */
public interface GenerationRoutingPolicy {

    Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal);
}
