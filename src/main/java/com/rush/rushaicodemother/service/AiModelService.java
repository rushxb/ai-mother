package com.rush.rushaicodemother.service;

import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;

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
     * 获取所有可执行的启用模型
     */
    List<AiModel> listRunnableEnabledModels();

    /**
     * 获取指定类型的可执行启用模型
     */
    List<AiModel> listRunnableEnabledModelsByType(String modelType);

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
     * 使用当前表单配置测试模型连接
     */
    AiModelConnectionTestResultVO testModelConnection(AiModel model);

    /**
     * 切换模型启用状态
     *
     * @param modelId 模型 ID
     * @return 更新后的模型
     */
    AiModel toggleModelEnabled(Long modelId);

    /**
     * 新增模型，并根据启用状态维护单活模型约束。
     *
     * @param model 模型配置
     * @return 是否保存成功
     */
    boolean saveModel(AiModel model);

    /**
     * 更新模型，并根据启用状态维护单活模型约束。
     *
     * @param model 模型配置
     * @return 是否更新成功
     */
    boolean updateModel(AiModel model);

    /**
     * 删除模型，并维护启用模型缓存。
     *
     * @param modelId 模型 ID
     * @return 是否删除成功
     */
    boolean deleteModel(Long modelId);

    /**
     * 清理启用模型缓存。
     */
    void evictEnabledModelCache();
}
