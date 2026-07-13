package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.rush.rushaicodemother.common.query.SortFieldWhitelist;
import com.rush.rushaicodemother.constant.RedisKeyConstant;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelQueryRequest;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.service.AiModelCatalogService;
import com.rush.rushaicodemother.service.AiModelService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * AI 模型配置 服务层实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelServiceImpl extends ServiceImpl<AiModelMapper, AiModel> implements AiModelService {

    private static final SortFieldWhitelist SORT_FIELDS = SortFieldWhitelist.of("sortOrder", Map.of(
            "modelName", "modelName",
            "provider", "provider",
            "modelId", "modelId",
            "modelType", "modelType",
            "isEnabled", "isEnabled",
            "sortOrder", "sortOrder",
            "createTime", "createTime",
            "updateTime", "updateTime"
    ));

    private final AiModelCatalogService aiModelCatalogService;

    private final StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void purgeLegacySensitiveCache() {
        evictEnabledModelCache();
    }

    @Override
    public List<AiModel> listEnabledModels() {
        // 运行时模型配置包含密钥，禁止写入 Redis 等共享缓存。
        return listEnabledModelsFromDb();
    }

    @Override
    public List<AiModel> listEnabledModelsByType(String modelType) {
        return listEnabledModels().stream()
                .filter(model -> StrUtil.equals(model.getModelType(), modelType))
                .toList();
    }

    @Override
    public List<AiModel> listRunnableEnabledModels() {
        return listEnabledModels().stream()
                .filter(aiModelCatalogService::isRunnable)
                .toList();
    }

    @Override
    public List<AiModel> listRunnableEnabledModelsByType(String modelType) {
        return listRunnableEnabledModels().stream()
                .filter(model -> StrUtil.equals(model.getModelType(), modelType))
                .toList();
    }

    @Override
    public void ensureGenerationModelsConfigured() {
        boolean hasFastModel = !listRunnableEnabledModelsByType("chat").isEmpty();
        boolean hasThinkingModel = !listRunnableEnabledModelsByType("reasoning").isEmpty();
        if (!hasFastModel || !hasThinkingModel) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "请联系系统管理员配置模型");
        }
    }

    private List<AiModel> listEnabledModelsFromDb() {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("isEnabled", 1);
        queryWrapper.eq("isDelete", 0);
        queryWrapper.orderBy("sortOrder", true);
        return this.mapper.selectListByQuery(queryWrapper).stream()
                .map(aiModelCatalogService::normalizeForRuntime)
                .toList();
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
        AiModelConnectionTestResultVO result = testModelConnection(model);
        return Boolean.TRUE.equals(result.getSuccess());
    }

    @Override
    public AiModelConnectionTestResultVO testModelConnection(AiModel model) {
        AiModel validatedModel = aiModelCatalogService.normalizeForRuntime(model);
        try {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                    .apiKey(validatedModel.getApiKey())
                    .baseUrl(validatedModel.getBaseUrl())
                    .modelName(validatedModel.getModelId())
                    .temperature(validatedModel.getTemperature())
                    .logRequests(false)
                    .logResponses(false);
            applyTestMaxTokens(builder, validatedModel);
            OpenAiChatModel chatModel = builder.build();

            String response = chatModel.chat("Hello, this is a connection test. Reply with 'OK' only.");
            log.info("模型连接测试成功，模型={}，响应={}", validatedModel.getModelName(), response);
            return AiModelConnectionTestResultVO.builder()
                    .success(StrUtil.isNotBlank(response))
                    .message("模型连接测试成功")
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("模型连接测试失败，模型={}，错误={}", validatedModel.getModelName(), e.getMessage(), e);
            return AiModelConnectionTestResultVO.builder()
                    .success(false)
                    .message(AiModelConnectionErrorMessageResolver.resolve(e))
                    .build();
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
            aiModelCatalogService.normalizeAndValidate(model);
            disableOtherEnabledModels(model.getModelType(), model.getId());
            model.setIsEnabled(1);
        } else {
            model.setIsEnabled(0);
        }
        this.updateById(model);
        evictEnabledModelCache();
        return model;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveModel(AiModel model) {
        if (model == null) {
            return false;
        }
        aiModelCatalogService.normalizeAndValidate(model);
        if (Integer.valueOf(1).equals(model.getIsEnabled())) {
            disableOtherEnabledModels(model.getModelType(), null);
        }
        boolean result = this.save(model);
        if (result) {
            evictEnabledModelCache();
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateModel(AiModel model) {
        if (model == null || model.getId() == null) {
            return false;
        }
        AiModel existingModel = this.getById(model.getId());
        if (existingModel == null) {
            return false;
        }

        // null 表示字段未提交；空白 API Key 表示保留原密钥。管理端响应不会回传密钥原文。
        String existingApiKey = existingModel.getApiKey();
        cn.hutool.core.bean.BeanUtil.copyProperties(
                model,
                existingModel,
                cn.hutool.core.bean.copier.CopyOptions.create().ignoreNullValue()
        );
        if (StrUtil.isBlank(model.getApiKey())) {
            existingModel.setApiKey(existingApiKey);
        }

        aiModelCatalogService.normalizeAndValidate(existingModel);
        if (Integer.valueOf(1).equals(existingModel.getIsEnabled())) {
            disableOtherEnabledModels(existingModel.getModelType(), existingModel.getId());
        }
        boolean result = this.updateById(existingModel);
        if (result) {
            evictEnabledModelCache();
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
            evictEnabledModelCache();
        }
        return result;
    }

    @Override
    public void evictEnabledModelCache() {
        // 新版本不再缓存运行时密钥；这里同时清理旧版本可能遗留的明文缓存。
        try {
            stringRedisTemplate.delete(RedisKeyConstant.AI_MODEL_ENABLED_LIST);
        } catch (Exception e) {
            log.warn("Failed to remove legacy AI model cache", e);
        }
    }

    private void applyTestMaxTokens(OpenAiChatModel.OpenAiChatModelBuilder builder, AiModel model) {
        int maxTokens = Math.min(model.getMaxTokens(), 256);
        if (isXiaomiMimoModel(model)) {
            builder.maxCompletionTokens(maxTokens);
            return;
        }
        builder.maxTokens(maxTokens);
    }

    private boolean isXiaomiMimoModel(AiModel model) {
        String provider = model.getProvider() != null ? model.getProvider().toLowerCase() : "";
        String modelId = model.getModelId() != null ? model.getModelId().toLowerCase() : "";
        return provider.equals("xiaomi") || modelId.startsWith("mimo-v2");
    }

    /**
     * 构造查询条件
     */
    @Override
    public QueryWrapper getQueryWrapper(AiModelQueryRequest queryRequest) {
        if (queryRequest == null) {
            return new QueryWrapper();
        }

        String provider = queryRequest.getProvider();
        String modelType = queryRequest.getModelType();
        Integer isEnabled = queryRequest.getIsEnabled();
        String keyword = queryRequest.getKeyword();
        String sortField = SORT_FIELDS.resolve(queryRequest.getSortField());
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

    private void disableOtherEnabledModels(String modelType, Long excludeId) {
        AiModel updateEntity = new AiModel();
        updateEntity.setIsEnabled(0);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("isEnabled", 1)
                .eq("isDelete", 0)
                .eq("modelType", modelType, StrUtil.isNotBlank(modelType));
        if (excludeId != null) {
            queryWrapper.ne("id", excludeId);
        }

        this.mapper.updateByQuery(updateEntity, queryWrapper);
    }


}
