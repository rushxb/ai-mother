package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;

/** Resolves the finite execution contract for one routing decision. */
public interface GenerationSlaPolicy {

    GenerationSlaEnvelope resolve(GenerationModeDecision decision, CodeGenTypeEnum targetType);
}
