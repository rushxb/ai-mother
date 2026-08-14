package com.rush.rushaicodemother.orchestration.pipeline;

/** 生成运行时中的一条可执行路径。 */
public interface GenerationPipeline {

    String route();

    /**
     * 返回提交准入与 worker 路由共享的静态能力声明。
     *
     * <p>默认失败只用于兼容旧测试 double；生产 pipeline 未声明能力时，registry 会拒绝启动。</p>
     */
    default GenerationPipelineCapability capability() {
        throw new IllegalStateException("生成管线未声明静态能力: " + route());
    }

    default boolean supports(GenerationPipelineRequest request) {
        return request != null && capability().supports(request.scenarioDecision());
    }

    GenerationPipelineOutcome execute(GenerationPipelineRequest request);
}
