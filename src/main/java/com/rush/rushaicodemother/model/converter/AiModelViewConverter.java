package com.rush.rushaicodemother.model.converter;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import org.springframework.stereotype.Component;

/**
 * AI 模型实体到 API 视图的唯一转换入口。
 * 集中维护敏感字段边界，禁止 Controller 直接序列化 AI 模型实体。
 */
@Component
public class AiModelViewConverter {

    public AiModelPublicVO toPublicVO(AiModel model) {
        if (model == null) {
            return null;
        }
        return AiModelPublicVO.builder()
                .id(model.getId())
                .modelName(model.getModelName())
                .provider(model.getProvider())
                .modelId(model.getModelId())
                .description(model.getDescription())
                .modelType(model.getModelType())
                .supportsThinking(model.getSupportsThinking())
                .sortOrder(model.getSortOrder())
                .build();
    }

    public AiModelAdminVO toAdminVO(AiModel model) {
        if (model == null) {
            return null;
        }
        return AiModelAdminVO.builder()
                .id(model.getId())
                .modelName(model.getModelName())
                .provider(model.getProvider())
                .modelId(model.getModelId())
                .description(model.getDescription())
                .baseUrl(model.getBaseUrl())
                .apiKeyConfigured(StrUtil.isNotBlank(model.getApiKey()))
                .maxTokens(model.getMaxTokens())
                .temperature(model.getTemperature())
                .isEnabled(model.getIsEnabled())
                .modelType(model.getModelType())
                .supportsThinking(model.getSupportsThinking())
                .sortOrder(model.getSortOrder())
                .configJson(model.getConfigJson())
                .userId(model.getUserId())
                .editTime(model.getEditTime())
                .createTime(model.getCreateTime())
                .updateTime(model.getUpdateTime())
                .build();
    }
}