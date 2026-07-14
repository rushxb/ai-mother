package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import org.springframework.stereotype.Component;

/** 将内部敏感配置转换为安全管理视图或公开视图。 */
@Component
public class AiModelViewAssembler {

    public AiModelPublicVO toPublicView(AiModelConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        return AiModelPublicVO.builder()
                .id(configuration.getId())
                .modelName(configuration.getModelName())
                .provider(configuration.getProvider())
                .modelId(configuration.getModelId())
                .description(configuration.getDescription())
                .modelType(configuration.getModelType())
                .supportsThinking(configuration.getSupportsThinking())
                .sortOrder(configuration.getSortOrder())
                .build();
    }

    public AiModelAdminVO toAdminView(AiModelConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        return AiModelAdminVO.builder()
                .id(configuration.getId())
                .modelName(configuration.getModelName())
                .provider(configuration.getProvider())
                .modelId(configuration.getModelId())
                .description(configuration.getDescription())
                .baseUrl(configuration.getBaseUrl())
                .apiKeyConfigured(StrUtil.isNotBlank(configuration.getApiKey()))
                .maxTokens(configuration.getMaxTokens())
                .temperature(configuration.getTemperature())
                .isEnabled(configuration.getIsEnabled())
                .modelType(configuration.getModelType())
                .supportsThinking(configuration.getSupportsThinking())
                .sortOrder(configuration.getSortOrder())
                .configJson(configuration.getConfigJson())
                .userId(configuration.getUserId())
                .editTime(configuration.getEditTime())
                .createTime(configuration.getCreateTime())
                .updateTime(configuration.getUpdateTime())
                .build();
    }
}
