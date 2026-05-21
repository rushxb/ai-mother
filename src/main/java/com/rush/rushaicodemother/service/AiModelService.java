package com.rush.rushaicodemother.service;

import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.model.entity.AiModel;

import java.util.List;

/**
 * AI 模型配置 服务层。
 */
public interface AiModelService extends IService<AiModel> {

    /**
     * 获取所有启用的模型
     *
     * @return 启用的模型列表
     */
    List<AiModel> listEnabledModels();

    /**
     * 根据模型类型获取启用的模型
     *
     * @param modelType 模型类型：chat/reasoning/routing
     * @return 模型列表
     */
    List<AiModel> listEnabledModelsByType(String modelType);

    /**
     * 根据提供商和模型ID获取模型
     *
     * @param provider 模型提供商
     * @param modelId  模型标识符
     * @return 模型配置
     */
    AiModel getByProviderAndModelId(String provider, String modelId);

    /**
     * 测试模型连接
     *
     * @param modelId 模型 ID
     * @return 测试结果
     */
    boolean testModelConnection(Long modelId);

    /**
     * 切换模型启用状态
     *
     * @param modelId 模型 ID
     * @return 更新后的模型
     */
    AiModel toggleModelEnabled(Long modelId);
}
