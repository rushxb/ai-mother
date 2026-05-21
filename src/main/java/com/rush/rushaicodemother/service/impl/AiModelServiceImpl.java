package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.rush.rushaicodemother.controller.AiModelController.AiModelQueryRequest;
import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.service.AiModelService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 模型配置 服务层实现。
 */
@Slf4j
@Service
public class AiModelServiceImpl extends ServiceImpl<AiModelMapper, AiModel> implements AiModelService {

    @Override
    public List<AiModel> listEnabledModels() {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("isEnabled", 1);
        queryWrapper.orderBy("sortOrder", true);
        return this.mapper.selectListByQuery(queryWrapper);
    }

    @Override
    public List<AiModel> listEnabledModelsByType(String modelType) {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("isEnabled", 1);
        queryWrapper.eq("modelType", modelType);
        queryWrapper.orderBy("sortOrder", true);
        return this.mapper.selectListByQuery(queryWrapper);
    }

    @Override
    public AiModel getByProviderAndModelId(String provider, String modelId) {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("provider", provider);
        queryWrapper.eq("modelId", modelId);
        queryWrapper.eq("isDelete", 0);
        return this.mapper.selectOneByQuery(queryWrapper);
    }

    @Override
    public boolean testModelConnection(Long modelId) {
        AiModel model = this.getById(modelId);
        if (model == null) {
            log.warn("测试连接失败：模型不存在，ID={}", modelId);
            return false;
        }

        try {
            // 构建一个简单的测试请求
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .apiKey(model.getApiKey())
                    .baseUrl(model.getBaseUrl())
                    .modelName(model.getModelId())
                    .maxTokens(100)
                    .temperature(0.7)
                    .logRequests(false)
                    .logResponses(false)
                    .build();

            // 发送测试消息
            String response = chatModel.chat("Hello, this is a connection test. Reply with 'OK' only.");
            log.info("模型连接测试成功，模型={}，响应={}", model.getModelName(), response);
            return response != null && !response.isEmpty();
        } catch (Exception e) {
            log.error("模型连接测试失败，模型={}，错误={}", model.getModelName(), e.getMessage(), e);
            return false;
        }
    }

    @Override
    public AiModel toggleModelEnabled(Long modelId) {
        AiModel model = this.getById(modelId);
        if (model == null) {
            return null;
        }

        // 切换启用状态
        model.setIsEnabled(model.getIsEnabled() == 1 ? 0 : 1);
        this.updateById(model);
        return model;
    }

    /**
     * 构造查询条件
     */
    public QueryWrapper getQueryWrapper(AiModelQueryRequest queryRequest) {
        if (queryRequest == null) {
            return new QueryWrapper();
        }

        String provider = queryRequest.getProvider();
        String modelType = queryRequest.getModelType();
        Integer isEnabled = queryRequest.getIsEnabled();
        String keyword = queryRequest.getKeyword();
        String sortField = queryRequest.getSortField();
        String sortOrder = queryRequest.getSortOrder();

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("provider", provider, StrUtil.isNotBlank(provider))
                .eq("modelType", modelType, StrUtil.isNotBlank(modelType))
                .eq("isEnabled", isEnabled, isEnabled != null);

        if (StrUtil.isNotBlank(keyword)) {
            String likeValue = "%" + keyword + "%";
            queryWrapper.and(
                    new com.mybatisflex.core.query.QueryColumn("modelName").like(likeValue)
                            .or(new com.mybatisflex.core.query.QueryColumn("modelId").like(likeValue))
                            .or(new com.mybatisflex.core.query.QueryColumn("description").like(likeValue))
            );
        }

        queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        return queryWrapper;
    }
}
