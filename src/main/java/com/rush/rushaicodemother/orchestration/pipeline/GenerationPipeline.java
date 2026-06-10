package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.orchestration.GenerationTaskResult;

import java.util.Optional;

/**
 * One route in the generation pipeline.
 */
public interface GenerationPipeline {

    String route();

    boolean supports(GenerationPipelineRequest request);

    Optional<GenerationTaskResult> execute(GenerationPipelineRequest request);
}
