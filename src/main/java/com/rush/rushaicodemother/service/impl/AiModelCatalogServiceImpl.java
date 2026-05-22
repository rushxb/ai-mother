package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;
import com.rush.rushaicodemother.service.AiModelCatalogService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 系统支持的 AI 模型目录。
 */
@Service
public class AiModelCatalogServiceImpl implements AiModelCatalogService {

    private static final List<SupportedModelDefinition> SUPPORTED_MODELS = List.of(
            new SupportedModelDefinition(
                    "deepseek", "DeepSeek", "deepseek-v4-flash", "DeepSeek V4 Flash",
                    "https://api.deepseek.com", List.of("https://api.deepseek.com"), List.of("chat", "routing"), "chat",
                    false, 8192, 0.7, 0.0, 2.0
            ),
            new SupportedModelDefinition(
                    "deepseek", "DeepSeek", "deepseek-v4-pro", "DeepSeek V4 Pro",
                    "https://api.deepseek.com", List.of("https://api.deepseek.com"), List.of("reasoning", "chat"), "reasoning",
                    true, 16384, 0.1, 0.0, 2.0
            ),
            new SupportedModelDefinition(
                    "openai", "OpenAI", "gpt-4o", "GPT-4o",
                    "https://api.openai.com/v1", List.of("https://api.openai.com/v1"), List.of("chat", "routing"), "chat",
                    false, 8192, 0.7, 0.0, 2.0
            ),
            new SupportedModelDefinition(
                    "openai", "OpenAI", "gpt-4.1-mini", "GPT-4.1 Mini",
                    "https://api.openai.com/v1", List.of("https://api.openai.com/v1"), List.of("chat", "routing"), "chat",
                    false, 8192, 0.7, 0.0, 2.0
            ),
            new SupportedModelDefinition(
                    "openai", "OpenAI", "o3", "OpenAI o3",
                    "https://api.openai.com/v1", List.of("https://api.openai.com/v1"), List.of("reasoning"), "reasoning",
                    true, 16384, 0.7, 0.0, 2.0
            ),
            new SupportedModelDefinition(
                    "muskapi", "MuskAPI", "gpt-5.5", "GPT-5.5",
                    "https://api.muskapi.cc/v1", List.of("https://api.muskapi.cc/v1", "https://api.muskapi.cc"), List.of("reasoning", "chat"), "reasoning",
                    true, 16384, 0.7, 0.0, 2.0
            )
    );

    @Override
    public List<SupportedAiModelVO> listSupportedModels() {
        return SUPPORTED_MODELS.stream()
                .map(model -> SupportedAiModelVO.builder()
                        .provider(model.provider())
                        .providerLabel(model.providerLabel())
                        .modelId(model.modelId())
                        .modelName(model.modelName())
                        .defaultBaseUrl(model.defaultBaseUrl())
                        .supportedModelTypes(model.supportedModelTypes())
                        .defaultModelType(model.defaultModelType())
                        .supportsThinking(model.supportsThinking() ? 1 : 0)
                        .defaultMaxTokens(model.defaultMaxTokens())
                        .defaultTemperature(model.defaultTemperature())
                        .minTemperature(model.minTemperature())
                        .maxTemperature(model.maxTemperature())
                        .build())
                .toList();
    }

    @Override
    public Optional<SupportedModelDefinition> findSupportedModel(String provider, String modelId) {
        String normalizedProvider = normalizeKey(provider);
        String normalizedModelId = normalizeKey(modelId);
        return SUPPORTED_MODELS.stream()
                .filter(model -> model.provider().equals(normalizedProvider) && model.modelId().equals(normalizedModelId))
                .findFirst();
    }

    @Override
    public AiModel normalizeAndValidate(AiModel model) {
        if (model == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型配置不能为空");
        }
        String provider = normalizeKey(model.getProvider());
        String modelId = normalizeKey(model.getModelId());
        String modelName = StrUtil.trim(model.getModelName());
        String baseUrl = normalizeBaseUrl(model.getBaseUrl());
        String apiKey = StrUtil.trim(model.getApiKey());
        String modelType = normalizeKey(model.getModelType());
        String description = StrUtil.trim(model.getDescription());

        if (StrUtil.hasBlank(provider, modelId, modelName, apiKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型名称、提供商、模型标识符和 API 密钥不能为空");
        }

        SupportedModelDefinition definition = findSupportedModel(provider, modelId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PARAMS_ERROR,
                        "当前仅支持系统目录中的模型，请从支持列表中选择"
                ));

        String expectedBaseUrl = normalizeBaseUrl(definition.defaultBaseUrl());
        if (StrUtil.isBlank(baseUrl)) {
            baseUrl = expectedBaseUrl;
        }
        if (!isCompatibleBaseUrl(definition, baseUrl)) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "模型接口地址不匹配，" + definition.modelName() + " 必须使用 " + expectedBaseUrl
            );
        }
        baseUrl = expectedBaseUrl;

        if (StrUtil.isBlank(modelType)) {
            modelType = definition.defaultModelType();
        }
        if (!definition.supportedModelTypes().contains(modelType)) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "模型类型不匹配，" + definition.modelName() + " 仅支持 " + String.join(" / ", definition.supportedModelTypes())
            );
        }

        Integer maxTokens = model.getMaxTokens();
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = definition.defaultMaxTokens();
        }

        Double temperature = model.getTemperature();
        if (temperature == null) {
            temperature = definition.defaultTemperature();
        }
        if (temperature < definition.minTemperature() || temperature > definition.maxTemperature()) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "温度参数超出范围，当前模型仅支持 " + definition.minTemperature() + " - " + definition.maxTemperature()
            );
        }

        model.setProvider(provider);
        model.setModelId(modelId);
        model.setModelName(modelName);
        model.setBaseUrl(baseUrl);
        model.setApiKey(apiKey);
        model.setModelType(modelType);
        model.setDescription(description);
        model.setMaxTokens(maxTokens);
        model.setTemperature(temperature);
        model.setSupportsThinking(definition.supportsThinking() ? 1 : 0);
        return model;
    }

    @Override
    public boolean isRunnable(AiModel model) {
        if (model == null) {
            return false;
        }
        Optional<SupportedModelDefinition> definitionOptional = findSupportedModel(model.getProvider(), model.getModelId());
        if (definitionOptional.isEmpty()) {
            return false;
        }
        SupportedModelDefinition definition = definitionOptional.get();
        String baseUrl = normalizeBaseUrl(model.getBaseUrl());
        return StrUtil.isNotBlank(StrUtil.trim(model.getApiKey()))
                && isCompatibleBaseUrl(definition, baseUrl)
                && definition.supportedModelTypes().contains(normalizeKey(model.getModelType()));
    }

    @Override
    public AiModel normalizeForRuntime(AiModel model) {
        AiModel normalizedModel = normalizeAndValidate(model);
        findSupportedModel(normalizedModel.getProvider(), normalizedModel.getModelId())
                .ifPresent(definition -> normalizedModel.setBaseUrl(normalizeBaseUrl(definition.defaultBaseUrl())));
        return normalizedModel;
    }

    private String normalizeKey(String value) {
        return StrUtil.blankToDefault(StrUtil.trim(value), "").toLowerCase(Locale.ROOT);
    }

    private String normalizeBaseUrl(String value) {
        String trimmed = StrUtil.trim(value);
        if (StrUtil.isBlank(trimmed)) {
            return "";
        }
        try {
            URI uri = new URI(trimmed);
            String normalized = uri.normalize().toString();
            return StrUtil.removeSuffix(normalized, "/");
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "API 地址格式错误");
        }
    }

    private boolean isCompatibleBaseUrl(SupportedModelDefinition definition, String baseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        return definition.compatibleBaseUrls().stream()
                .map(this::normalizeBaseUrl)
                .anyMatch(compatibleUrl -> StrUtil.equals(compatibleUrl, normalizedBaseUrl));
    }
}
