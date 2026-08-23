package com.rush.rushaicodemother.service.aimodel;

import java.util.List;

/** 对有序模型池的仅敏感运行时访问。 */
public interface AiModelRuntimeService {

    /** 返回电路当前已关闭的最高优先级可运行模型。 */
    AiModelRuntimeConfiguration requireRunnableModelByType(String modelType);

    /**
     * 返回用于请求级故障转移的健康的、按优先级排序的运行时池。
     *
     * @throws AiModelPoolUnavailableException 指定类型没有可运行候选；调用方可按用途决定是否降级
     */
    List<AiModelRuntimeConfiguration> listRunnableModelsByType(String modelType);

    /** 验证这一代人至少有一种健康的聊天和推理模型。 */
    void ensureGenerationModelsConfigured();
}
