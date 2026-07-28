package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.vo.AiModelAdminVO;
import com.rush.rushaicodemother.model.vo.AiModelPublicVO;
import org.springframework.stereotype.Component;

/** 将内部敏感配置转换为安全管理视图或公开视图。 */
@Component
public class AiModelViewAssembler {

    /**
 * 将当前对象转换为公开视图。
 *
 * @param configuration 配置
 * @return 公开视图
 */
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

    /**
 * 将当前对象转换为管理端视图。
 *
 * @param configuration 配置
 * @return 管理端视图
 */
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
                .apiKeyConfigured(StrUtil.isNotBlank(configuration.getSecretRef())
                        && StrUtil.isNotBlank(configuration.getSecretFingerprint()))
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
