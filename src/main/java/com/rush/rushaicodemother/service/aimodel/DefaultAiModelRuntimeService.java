package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 从启用的、按排序顺序的后备池中选择第一个运行状况良好的运行时模型。 */
@Service
@RequiredArgsConstructor
public class DefaultAiModelRuntimeService implements AiModelRuntimeService {

    private static final String CHAT_MODEL_TYPE = "chat";
    private static final String REASONING_MODEL_TYPE = "reasoning";

    private final AiModelEnabledConfigurationSource configurationSource;
    private final AiModelConfigurationPolicy configurationPolicy;
    private final AiModelCircuitBreaker circuitBreaker;

    @Override
    public AiModelRuntimeConfiguration requireRunnableModelByType(String modelType) {
        return listRunnableModelsByType(modelType).getFirst();
    }

    @Override
    public List<AiModelRuntimeConfiguration> listRunnableModelsByType(String modelType) {
        if (StrUtil.isBlank(modelType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "model type cannot be blank");
        }
        List<AiModelRuntimeConfiguration> candidates = configurationSource.findEnabled(modelType).stream()
                .filter(configurationPolicy::isRunnable)
                .map(configurationPolicy::toRuntimeConfiguration)
                .toList();
        if (candidates.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "No runnable " + modelType + " model is configured"
            );
        }
        List<AiModelRuntimeConfiguration> available = candidates.stream()
                .filter(candidate -> circuitBreaker.isAvailable(candidate.provider(), candidate.modelId()))
                .toList();
        if (available.isEmpty()) {
            throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "All configured " + modelType + " models are temporarily unavailable"
                );
        }
        return available;
    }

    @Override
    public void ensureGenerationModelsConfigured() {
        requireRunnableModelByType(CHAT_MODEL_TYPE);
        requireRunnableModelByType(REASONING_MODEL_TYPE);
    }
}
