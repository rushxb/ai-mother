package com.rush.rushaicodemother.orchestration.learning;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 场景质量归因的只读查询端口。 */
public interface GenerationScenarioAttributionRepository {

    Optional<GenerationScenarioAttribution> findByTaskId(String taskId);

    List<GenerationScenarioBucketSummary> summarize(String intentSignature,
                                                     Instant from,
                                                     Instant to,
                                                     int limit);
}
