package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.security.AiModelOutboundDestinationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将管理命令显式装配为模块内部配置，保护创建人和审计字段。 */
@Component
@RequiredArgsConstructor
public class AiModelConfigurationAssembler {

    private static final int DEFAULT_ENABLED = 0;
    private static final int DEFAULT_SORT_ORDER = 0;

    private final AiModelSecretService secretService;
    private final AiModelOutboundDestinationPolicy outboundDestinationPolicy;

    /**
 * 根据输入数据创建当前对象。
 *
 * @param command 命令
 * @param operatorUserId 目标资源编号
 * @return AI 模型配置{@code Assembler}
 */
    public AiModelConfiguration fromCreateCommand(AiModelManagementService.CreateCommand command,
                                                   Long operatorUserId) {
        if (command == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型配置不能为空");
        }
        AiModelProtectedSecret protectedSecret = secretService.protect(command.apiKey());
        return AiModelConfiguration.builder()
                .modelName(command.modelName())
                .provider(command.provider())
                .modelId(command.modelId())
                .description(command.description())
                .baseUrl(command.baseUrl())
                .secretRef(protectedSecret.reference())
                .secretFingerprint(protectedSecret.fingerprint())
                .secretKeyId(protectedSecret.keyId())
                .maxTokens(command.maxTokens())
                .temperature(command.temperature())
                .isEnabled(command.isEnabled() == null ? DEFAULT_ENABLED : command.isEnabled())
                .modelType(command.modelType())
                .supportsThinking(command.supportsThinking())
                .sortOrder(command.sortOrder() == null ? DEFAULT_SORT_ORDER : command.sortOrder())
                .configJson(mergeProtocol(command.configJson(), command.protocol()))
                .userId(operatorUserId)
                .build();
    }

    /**
 * 应用{@code Update}。
 *
 * @param existing {@code existing} 对应的调用参数
 * @param command 命令
 * @return {@code Update}
 */
    public AiModelConfiguration applyUpdate(AiModelConfiguration existing,
                                            AiModelManagementService.UpdateCommand command) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (existing == null || command == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型更新参数不完整");
        }
        if (!hasEditableField(command)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "至少提供一个可更新字段");
        }
        outboundDestinationPolicy.requireReplacementSecretForDestinationChange(
                existing.getBaseUrl(), command.baseUrl(), command.apiKey());

        AiModelConfiguration.AiModelConfigurationBuilder builder = existing.toBuilder();
        if (command.modelName() != null) {
            builder.modelName(command.modelName());
        }
        if (command.provider() != null) {
            builder.provider(command.provider());
        }
        if (command.modelId() != null) {
            builder.modelId(command.modelId());
        }
        if (command.description() != null) {
            builder.description(command.description());
        }
        if (command.baseUrl() != null) {
            builder.baseUrl(command.baseUrl());
        }
        if (StrUtil.isNotBlank(command.apiKey())) {
            AiModelProtectedSecret protectedSecret = secretService.protect(command.apiKey());
            builder.secretRef(protectedSecret.reference())
                    .secretFingerprint(protectedSecret.fingerprint())
                    .secretKeyId(protectedSecret.keyId());
        }
        if (command.maxTokens() != null) {
            builder.maxTokens(command.maxTokens());
        }
        if (command.temperature() != null) {
            builder.temperature(command.temperature());
        }
        if (command.isEnabled() != null) {
            builder.isEnabled(command.isEnabled());
        }
        if (command.modelType() != null) {
            builder.modelType(command.modelType());
        }
        if (command.supportsThinking() != null) {
            builder.supportsThinking(command.supportsThinking());
        }
        if (command.sortOrder() != null) {
            builder.sortOrder(command.sortOrder());
        }
        if (command.configJson() != null || StrUtil.isNotBlank(command.protocol())) {
            String baseConfig = command.configJson() != null
                    ? command.configJson()
                    : existing.getConfigJson();
            builder.configJson(mergeProtocol(baseConfig, command.protocol()));
        }
        return builder.build();
    }

    /** 判断是否存在{@code Editable}{@code Field}。 */
    private boolean hasEditableField(AiModelManagementService.UpdateCommand command) {
        return command.modelName() != null
                || command.provider() != null
                || command.modelId() != null
                || command.description() != null
                || command.baseUrl() != null
                || StrUtil.isNotBlank(command.apiKey())
                || command.maxTokens() != null
                || command.temperature() != null
                || command.isEnabled() != null
                || command.modelType() != null
                || command.supportsThinking() != null
                || command.sortOrder() != null
                || command.configJson() != null
                || StrUtil.isNotBlank(command.protocol());
    }

    /** 合并{@code Protocol}。 */
    private String mergeProtocol(String configJson, String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return configJson;
        }
        try {
            JSONObject config = StrUtil.isBlank(configJson)
                    ? new JSONObject()
                    : JSONUtil.parseObj(configJson);
            config.set("protocol", StrUtil.trim(protocol));
            return JSONUtil.toJsonStr(config);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型扩展配置 JSON 格式错误", exception);
        }
    }
}
