package com.rush.rushaicodemother.service.aimodel;

import java.util.List;

/** Sensitive runtime-only access to the ordered model pool. */
public interface AiModelRuntimeService {

    /** Returns the highest-priority runnable model whose circuit is currently closed. */
    AiModelRuntimeConfiguration requireRunnableModelByType(String modelType);

    /** Returns the healthy, priority-ordered runtime pool used for request-level failover. */
    List<AiModelRuntimeConfiguration> listRunnableModelsByType(String modelType);

    /** Verifies that generation has at least one healthy chat and reasoning model. */
    void ensureGenerationModelsConfigured();
}
