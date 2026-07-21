package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Selects the first healthy runtime model from the enabled, sort-ordered fallback pool. */
@Service
@RequiredArgsConstructor
public class DefaultAiModelRuntimeService implements AiModelRuntimeService {

    private static final String CHAT_MODEL_TYPE = "chat";
    private static final String REASONING_MODEL_TYPE = "reasoning";

    private final AiModelPersistenceService persistenceService;
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
        List<AiModelRuntimeConfiguration> candidates = persistenceService.findEnabled(modelType).stream()
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
