package com.rush.rushaicodemother.orchestration.pipeline;

/** One executable route in the generation runtime. */
public interface GenerationPipeline {

    String route();

    boolean supports(GenerationPipelineRequest request);

    GenerationPipelineOutcome execute(GenerationPipelineRequest request);
}
