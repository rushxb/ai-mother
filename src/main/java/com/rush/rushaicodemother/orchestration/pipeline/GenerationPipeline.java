package com.rush.rushaicodemother.orchestration.pipeline;

/** 生成运行时中的一条可执行路径。 */
public interface GenerationPipeline {

    String route();

    boolean supports(GenerationPipelineRequest request);

    GenerationPipelineOutcome execute(GenerationPipelineRequest request);
}
