package com.rush.rushaicodemother.service.aimodel;

/** 仅供 AI 运行时使用的敏感配置入口。 */
public interface AiModelRuntimeService {

    /** 获取指定类型唯一启用且可执行的模型配置。 */
    AiModelRuntimeConfiguration requireRunnableModelByType(String modelType);

    /** 校验应用生成所需的快速模型和推理模型均已配置。 */
    void ensureGenerationModelsConfigured();
}
