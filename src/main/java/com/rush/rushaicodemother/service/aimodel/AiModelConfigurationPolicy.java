package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.vo.SupportedAiModelVO;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** AI 模型目录、协议归一化与可运行性规则。 */
@Component
public class AiModelConfigurationPolicy {

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

    public AiModelConfiguration normalizeAndValidate(AiModelConfiguration configuration) {
        if (configuration == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型配置不能为空");
        }
        String provider = normalizeKey(configuration.getProvider());
        String modelId = normalizeKey(configuration.getModelId());
        String modelName = StrUtil.trim(configuration.getModelName());
        String baseUrl = normalizeOpenAiBaseUrl(configuration.getBaseUrl());
        String apiKey = StrUtil.trim(configuration.getApiKey());
        String modelType = normalizeKey(configuration.getModelType());
        String description = StrUtil.trim(configuration.getDescription());
        String configJson = normalizeConfigJson(configuration.getConfigJson());

        if (StrUtil.hasBlank(provider, modelId, modelName, baseUrl, apiKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "模型名称、提供商、模型标识符、API 地址和 API 密钥不能为空");
        }

        Optional<SupportedModelDefinition> definition = findSupportedModel(provider, modelId);
        if (StrUtil.isBlank(modelType)) {
            modelType = definition.map(SupportedModelDefinition::defaultModelType).orElse("chat");
        }
        if (definition.isPresent() && !definition.get().supportedModelTypes().contains(modelType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "模型类型不匹配，" + definition.get().modelName() + " 仅支持 "
                            + String.join(" / ", definition.get().supportedModelTypes()));
        }
        if (definition.isEmpty() && !MODEL_TYPES.contains(modelType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型类型仅支持 chat / reasoning / routing");
        }

        Integer maxTokens = configuration.getMaxTokens();
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = definition.map(SupportedModelDefinition::defaultMaxTokens).orElse(DEFAULT_MAX_TOKENS);
        }
        Double temperature = configuration.getTemperature();
        if (temperature == null) {
            temperature = definition.map(SupportedModelDefinition::defaultTemperature).orElse(DEFAULT_TEMPERATURE);
        }
        double minimum = definition.map(SupportedModelDefinition::minTemperature).orElse(0.0);
        double maximum = definition.map(SupportedModelDefinition::maxTemperature).orElse(2.0);
        if (temperature < minimum || temperature > maximum) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "温度参数超出范围，当前模型仅支持 " + minimum + " - " + maximum);
        }

        return configuration.toBuilder()
                .provider(provider)
                .modelId(modelId)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelType(modelType)
                .description(description)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .supportsThinking(definition
                        .map(item -> item.supportsThinking() ? 1 : 0)
                        .orElse(configuration.thinkingSupported() ? 1 : 0))
                .configJson(configJson)
                .build();
    }

    public boolean isRunnable(AiModelConfiguration configuration) {
        try {
            normalizeAndValidate(configuration);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    public AiModelRuntimeConfiguration toRuntimeConfiguration(AiModelConfiguration configuration) {
        AiModelConfiguration normalized = normalizeAndValidate(configuration);
        return new AiModelRuntimeConfiguration(
                normalized.getProvider(),
                normalized.getModelId(),
                normalized.getModelType(),
                normalized.getBaseUrl(),
                normalized.getApiKey(),
                normalized.getMaxTokens(),
                normalized.getTemperature(),
                normalized.thinkingSupported()
        );
    }

    private Optional<SupportedModelDefinition> findSupportedModel(String provider, String modelId) {
        String normalizedProvider = normalizeKey(provider);
        String normalizedModelId = normalizeKey(modelId);
        return SUPPORTED_MODELS.stream()
                .filter(model -> model.provider().equals(normalizedProvider)
                        && model.modelId().equals(normalizedModelId))
                .findFirst();
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
            URI uri = new URI(trimmed).normalize();
            String scheme = normalizeKey(uri.getScheme());
            if (!("http".equals(scheme) || "https".equals(scheme)) || StrUtil.isBlank(uri.getHost())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "API 地址仅支持包含主机名的 HTTP 或 HTTPS 地址");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "API 地址不能包含用户信息、查询参数或片段");
            }
            return StrUtil.removeSuffix(uri.toString(), "/");
        } catch (URISyntaxException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "API 地址格式错误", exception);
        }
    }

    private String normalizeOpenAiBaseUrl(String value) {
        String baseUrl = normalizeBaseUrl(value);
        if (StrUtil.isBlank(baseUrl)) {
            return "";
        }
        try {
            URI uri = new URI(baseUrl);
            String path = StrUtil.removeSuffix(StrUtil.blankToDefault(uri.getPath(), ""), "/");
            if (StrUtil.isBlank(path) || StrUtil.equals(path, "/chat/completions")) {
                return withPath(uri, "/v1");
            }
            if (StrUtil.endWith(path, "/chat/completions")) {
                return withPath(uri, StrUtil.blankToDefault(
                        StrUtil.removeSuffix(path, "/chat/completions"), "/v1"));
            }
            return baseUrl;
        } catch (URISyntaxException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "API 地址格式错误", exception);
        }
    }

    private String withPath(URI uri, String path) throws URISyntaxException {
        return normalizeBaseUrl(new URI(
                uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null
        ).toString());
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

    private JSONObject parseConfigJson(String configJson) {
        if (StrUtil.isBlank(configJson)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(configJson);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型扩展配置 JSON 格式错误", exception);
        }
    }

    private String normalizeProtocol(String protocol) {
        String normalized = normalizeKey(protocol).replace('-', '_');
        if (StrUtil.isBlank(normalized)
                || StrUtil.equals(normalized, "openai_chat_completion")
                || StrUtil.equals(normalized, "openai")) {
            return DEFAULT_PROTOCOL;
        }
        return normalized;
    }

    private record SupportedModelDefinition(String provider,
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
                                            double maxTemperature) {
    }
}
