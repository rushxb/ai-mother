package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;

import java.util.List;
import java.util.Optional;

/**
 * AI 模型支持目录服务。
 */
public interface AiModelCatalogService {

    /**
     * 获取系统支持的模型目录
     */
    List<SupportedAiModelVO> listSupportedModels();

    /**
     * 按 provider + modelId 查找支持目录
     */
    Optional<SupportedModelDefinition> findSupportedModel(String provider, String modelId);

    /**
     * 归一化并校验模型配置。
     */
    AiModel normalizeAndValidate(AiModel model);

    /**
     * 判断模型是否可用于实际调用。
     */
    boolean isRunnable(AiModel model);

    /**
     * 归一化运行时模型配置，兼容历史配置并输出系统当前适配的调用参数。
     */
    AiModel normalizeForRuntime(AiModel model);

    /**
     * 支持模型定义
     */
    record SupportedModelDefinition(
            String provider,
            String providerLabel,
            String modelId,
            String modelName,
            String defaultBaseUrl,
            List<String> compatibleBaseUrls,
            List<String> supportedModelTypes,
            String defaultModelType,
            boolean supportsThinking,
            int defaultMaxTokens,
            double defaultTemperature,
            double minTemperature,
            double maxTemperature
    ) {
    }
}
