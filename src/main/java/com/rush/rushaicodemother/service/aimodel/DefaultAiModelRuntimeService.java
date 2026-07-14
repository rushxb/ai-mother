package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** AI 运行时敏感配置读取实现。 */
@Service
@RequiredArgsConstructor
public class DefaultAiModelRuntimeService implements AiModelRuntimeService {

    private static final String CHAT_MODEL_TYPE = "chat";
    private static final String REASONING_MODEL_TYPE = "reasoning";

    private final AiModelPersistenceService persistenceService;
    private final AiModelConfigurationPolicy configurationPolicy;

    @Override
    public AiModelRuntimeConfiguration requireRunnableModelByType(String modelType) {
        if (StrUtil.isBlank(modelType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型类型不能为空");
        }
        return persistenceService.findEnabled(modelType).stream()
                .filter(configurationPolicy::isRunnable)
                .findFirst()
                .map(configurationPolicy::toRuntimeConfiguration)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "请联系系统管理员配置可用的 " + modelType + " 模型"
                ));
    }

    @Override
    public void ensureGenerationModelsConfigured() {
        requireRunnableModelByType(CHAT_MODEL_TYPE);
        requireRunnableModelByType(REASONING_MODEL_TYPE);
    }
}
