package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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

    private static final String DEFAULT_PROTOCOL = "openai_chat_completions";

    private static final int DEFAULT_MAX_TOKENS = 8192;

    private static final double DEFAULT_TEMPERATURE = 0.7;

    private static final List<String> MODEL_TYPES = List.of("chat", "reasoning", "routing");

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
            ),
            new SupportedModelDefinition(
                    "xiaomi", "Xiaomi MiMo", "mimo-v2.5-pro", "MiMo V2.5 Pro",
                    "https://api.xiaomimimo.com/v1", List.of("https://api.xiaomimimo.com/v1"), List.of("reasoning", "chat"), "reasoning",
                    true, 32768, 1.0, 0.0, 1.5
            ),
            new SupportedModelDefinition(
                    "xiaomi", "Xiaomi MiMo", "mimo-v2.5", "MiMo V2.5",
                    "https://api.xiaomimimo.com/v1", List.of("https://api.xiaomimimo.com/v1"), List.of("reasoning", "chat"), "reasoning",
                    true, 32768, 1.0, 0.0, 1.5
            ),
            new SupportedModelDefinition(
                    "xiaomi", "Xiaomi MiMo", "mimo-v2-pro", "MiMo V2 Pro",
                    "https://api.xiaomimimo.com/v1", List.of("https://api.xiaomimimo.com/v1"), List.of("reasoning", "chat"), "reasoning",
                    true, 32768, 1.0, 0.0, 1.5
            ),
            new SupportedModelDefinition(
                    "xiaomi", "Xiaomi MiMo", "mimo-v2-omni", "MiMo V2 Omni",
                    "https://api.xiaomimimo.com/v1", List.of("https://api.xiaomimimo.com/v1"), List.of("reasoning", "chat"), "reasoning",
                    true, 32768, 1.0, 0.0, 1.5
            ),
            new SupportedModelDefinition(
                    "xiaomi", "Xiaomi MiMo", "mimo-v2-flash", "MiMo V2 Flash",
                    "https://api.xiaomimimo.com/v1", List.of("https://api.xiaomimimo.com/v1"), List.of("chat", "routing"), "chat",
                    true, 8192, 0.3, 0.0, 1.5
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
                        .defaultProtocol(DEFAULT_PROTOCOL)
                        .supportedProtocols(List.of(DEFAULT_PROTOCOL))
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
        String baseUrl = normalizeOpenAiChatCompletionsBaseUrl(model.getBaseUrl());
        String apiKey = StrUtil.trim(model.getApiKey());
        String modelType = normalizeKey(model.getModelType());
        String description = StrUtil.trim(model.getDescription());
        String configJson = normalizeConfigJson(model.getConfigJson());

        if (StrUtil.hasBlank(provider, modelId, modelName, baseUrl, apiKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型名称、提供商、模型标识符、API 地址和 API 密钥不能为空");
        }

        Optional<SupportedModelDefinition> definitionOptional = findSupportedModel(provider, modelId);

        if (StrUtil.isBlank(modelType)) {
            modelType = definitionOptional.map(SupportedModelDefinition::defaultModelType).orElse("chat");
        }
        if (definitionOptional.isPresent()) {
            SupportedModelDefinition definition = definitionOptional.get();
            if (!definition.supportedModelTypes().contains(modelType)) {
                throw new BusinessException(
                        ErrorCode.PARAMS_ERROR,
                        "模型类型不匹配，" + definition.modelName() + " 仅支持 " + String.join(" / ", definition.supportedModelTypes())
                );
            }
        } else if (!MODEL_TYPES.contains(modelType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型类型仅支持 chat / reasoning / routing");
        }

        Integer maxTokens = model.getMaxTokens();
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = definitionOptional.map(SupportedModelDefinition::defaultMaxTokens).orElse(DEFAULT_MAX_TOKENS);
        }

        Double temperature = model.getTemperature();
        if (temperature == null) {
            temperature = definitionOptional.map(SupportedModelDefinition::defaultTemperature).orElse(DEFAULT_TEMPERATURE);
        }
        double minTemperature = definitionOptional.map(SupportedModelDefinition::minTemperature).orElse(0.0);
        double maxTemperature = definitionOptional.map(SupportedModelDefinition::maxTemperature).orElse(2.0);
        if (temperature < minTemperature || temperature > maxTemperature) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "温度参数超出范围，当前模型仅支持 " + minTemperature + " - " + maxTemperature
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
        model.setSupportsThinking(definitionOptional
                .map(definition -> definition.supportsThinking() ? 1 : 0)
                .orElse(Integer.valueOf(1).equals(model.getSupportsThinking()) ? 1 : 0));
        model.setConfigJson(configJson);
        return model;
    }

    @Override
    public boolean isRunnable(AiModel model) {
        if (model == null) {
            return false;
        }
        String baseUrl = normalizeOpenAiChatCompletionsBaseUrl(model.getBaseUrl());
        if (StrUtil.isBlank(StrUtil.trim(model.getApiKey()))
                || StrUtil.isBlank(baseUrl)
                || !isSupportedProtocol(model.getConfigJson())) {
            return false;
        }
        Optional<SupportedModelDefinition> definitionOptional = findSupportedModel(model.getProvider(), model.getModelId());
        String modelType = normalizeKey(model.getModelType());
        return definitionOptional
                .map(definition -> definition.supportedModelTypes().contains(modelType))
                .orElse(MODEL_TYPES.contains(modelType));
    }

    @Override
    public AiModel normalizeForRuntime(AiModel model) {
        return normalizeAndValidate(model);
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

    private String normalizeOpenAiChatCompletionsBaseUrl(String value) {
        String baseUrl = normalizeBaseUrl(value);
        if (StrUtil.isBlank(baseUrl)) {
            return "";
        }
        try {
            URI uri = new URI(baseUrl);
            String path = StrUtil.removeSuffix(StrUtil.blankToDefault(uri.getPath(), ""), "/");
            if (StrUtil.isBlank(path)) {
                return appendPath(uri, "/v1");
            }
            if (StrUtil.equals(path, "/chat/completions")) {
                return appendPath(uri, "/v1");
            }
            if (StrUtil.endWith(path, "/chat/completions")) {
                String basePath = StrUtil.removeSuffix(path, "/chat/completions");
                return appendPath(uri, StrUtil.blankToDefault(basePath, "/v1"));
            }
            return baseUrl;
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "API 地址格式错误");
        }
    }

    private String appendPath(URI uri, String path) throws URISyntaxException {
        URI normalizedUri = new URI(
                uri.getScheme(),
                uri.getUserInfo(),
                uri.getHost(),
                uri.getPort(),
                path,
                uri.getQuery(),
                uri.getFragment()
        );
        return normalizeBaseUrl(normalizedUri.toString());
    }

    private String normalizeConfigJson(String configJson) {
        JSONObject config = parseConfigJson(configJson);
        String protocol = normalizeProtocol(config.getStr("protocol"));
        if (!StrUtil.equals(protocol, DEFAULT_PROTOCOL)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前仅支持 OpenAI Chat Completions 协议");
        }
        config.set("protocol", DEFAULT_PROTOCOL);
        return JSONUtil.toJsonStr(config);
    }

    private boolean isSupportedProtocol(String configJson) {
        try {
            return StrUtil.equals(normalizeProtocol(parseConfigJson(configJson).getStr("protocol")), DEFAULT_PROTOCOL);
        } catch (BusinessException e) {
            return false;
        }
    }

    private JSONObject parseConfigJson(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(configJson);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型扩展配置 JSON 格式错误");
        }
    }

    private String normalizeProtocol(String protocol) {
        String normalized = normalizeKey(protocol);
        if (StrUtil.isBlank(normalized)) {
            return DEFAULT_PROTOCOL;
        }
        normalized = normalized.replace('-', '_');
        if (StrUtil.equals(normalized, "openai_chat_completions")
                || StrUtil.equals(normalized, "openai_chat_completion")
                || StrUtil.equals(normalized, "openai")) {
            return DEFAULT_PROTOCOL;
        }
        return normalized;
    }
}
