package com.rush.rushaicodemother.service.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.rush.rushaicodemother.controller.AiModelController.AiModelQueryRequest;
import com.rush.rushaicodemother.constant.RedisKeyConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.service.AiModelCatalogService;
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

    @Resource
    private AiModelCatalogService aiModelCatalogService;

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
                    .message(resolveConnectionErrorMessage(e))
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
        aiModelCatalogService.normalizeAndValidate(model);
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
        aiModelCatalogService.normalizeAndValidate(model);
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

    private String resolveConnectionErrorMessage(Exception e) {
        String rawMessage = e.getMessage();
        if (StrUtil.containsIgnoreCase(rawMessage, "Unexpected character ('<'")
                || StrUtil.containsIgnoreCase(rawMessage, "text/html")) {
            return "模型接口返回了 HTML 而不是 JSON，请检查是否使用了受支持的官方接口地址，或当前网关被拦截";
        }
        if (StrUtil.containsIgnoreCase(rawMessage, "401")
                || StrUtil.containsIgnoreCase(rawMessage, "403")) {
            return "模型认证失败，请检查 API Key 是否正确，或当前账号是否具备该模型权限";
        }
        return StrUtil.blankToDefault(rawMessage, "模型连接测试失败，请检查配置");
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
