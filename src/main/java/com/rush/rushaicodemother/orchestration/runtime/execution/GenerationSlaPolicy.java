package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;

/** 解决一个路由决策的有限执行契约。 */
public interface GenerationSlaPolicy {

    GenerationSlaEnvelope resolve(GenerationModeDecision decision, CodeGenTypeEnum targetType);
}
