package com.rush.rushaicodemother.service.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.rush.rushaicodemother.controller.AiModelController.AiModelQueryRequest;
import com.rush.rushaicodemother.constant.RedisKeyConstant;
import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.service.AiModelService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * AI 模型配置 服务层实现。
 */
@Slf4j
@Service
public class AiModelServiceImpl extends ServiceImpl<AiModelMapper, AiModel> implements AiModelService {

    private static final Duration ENABLED_MODEL_CACHE_TTL = Duration.ofHours(6);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<AiModel> listEnabledModels() {
        List<AiModel> cachedModels = getEnabledModelsFromCache();
        if (cachedModels != null) {
            return cachedModels;
        }
        List<AiModel> models = listEnabledModelsFromDb();
        refreshEnabledModelCache(models);
        return models;
    }

    @Override
    public List<AiModel> listEnabledModelsByType(String modelType) {
        return listEnabledModels().stream()
                .filter(model -> StrUtil.equals(model.getModelType(), modelType))
                .toList();
    }

    private List<AiModel> listEnabledModelsFromDb() {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("isEnabled", 1);
        queryWrapper.eq("isDelete", 0);
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
    @Transactional(rollbackFor = Exception.class)
    public AiModel toggleModelEnabled(Long modelId) {
        AiModel model = this.getById(modelId);
        if (model == null) {
            return null;
        }

        boolean enable = model.getIsEnabled() == null || model.getIsEnabled() != 1;
        if (enable) {
            disableOtherEnabledModels(model.getId());
            model.setIsEnabled(1);
        } else {
            model.setIsEnabled(0);
        }
        this.updateById(model);
        refreshEnabledModelCache();
        return model;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveModel(AiModel model) {
        if (model == null) {
            return false;
        }
        if (Integer.valueOf(1).equals(model.getIsEnabled())) {
            disableOtherEnabledModels(null);
        }
        boolean result = this.save(model);
        if (result) {
            refreshEnabledModelCache();
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateModel(AiModel model) {
        if (model == null || model.getId() == null) {
            return false;
        }
        if (Integer.valueOf(1).equals(model.getIsEnabled())) {
            disableOtherEnabledModels(model.getId());
        }
        boolean result = this.updateById(model);
        if (result) {
            refreshEnabledModelCache();
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteModel(Long modelId) {
        if (modelId == null || modelId <= 0) {
            return false;
        }
        boolean result = this.removeById(modelId);
        if (result) {
            refreshEnabledModelCache();
        }
        return result;
    }

    @Override
    public void evictEnabledModelCache() {
        stringRedisTemplate.delete(RedisKeyConstant.AI_MODEL_ENABLED_LIST);
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

    private void disableOtherEnabledModels(Long excludeId) {
        AiModel updateEntity = new AiModel();
        updateEntity.setIsEnabled(0);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("isEnabled", 1)
                .eq("isDelete", 0);
        if (excludeId != null) {
            queryWrapper.ne("id", excludeId);
        }

        this.mapper.updateByQuery(updateEntity, queryWrapper);
    }

    private List<AiModel> getEnabledModelsFromCache() {
        String cachedJson = stringRedisTemplate.opsForValue().get(RedisKeyConstant.AI_MODEL_ENABLED_LIST);
        if (StrUtil.isBlank(cachedJson)) {
            return null;
        }
        try {
            return JSONUtil.toList(cachedJson, AiModel.class);
        } catch (Exception e) {
            log.warn("解析启用模型缓存失败，将回退数据库查询并清理缓存", e);
            evictEnabledModelCache();
            return null;
        }
    }

    private void refreshEnabledModelCache() {
        refreshEnabledModelCache(listEnabledModelsFromDb());
    }

    private void refreshEnabledModelCache(List<AiModel> models) {
        if (models == null || models.isEmpty()) {
            evictEnabledModelCache();
            return;
        }
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstant.AI_MODEL_ENABLED_LIST,
                JSONUtil.toJsonStr(models),
                ENABLED_MODEL_CACHE_TTL
        );
    }
}
