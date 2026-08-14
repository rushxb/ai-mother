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

    /**
 * 校验并返回有效的可运行模型按类型。
 *
 * @param modelType 模型类型
 * @return 可运行模型按类型
 */
    @Override
    public AiModelRuntimeConfiguration requireRunnableModelByType(String modelType) {
        return listRunnableModelsByType(modelType).getFirst();
    }

    /**
 * 列出符合条件的可运行模型按类型。
 *
 * @param modelType 模型类型
 * @return 可运行模型按类型集合
 */
    @Override
    public List<AiModelRuntimeConfiguration> listRunnableModelsByType(String modelType) {
        if (StrUtil.isBlank(modelType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "model type cannot be blank");
        }
        List<AiModelRuntimeConfiguration> candidates = configurationSource.findEnabled(modelType).stream()
                .flatMap(configuration -> configurationPolicy
                        .toRuntimeConfigurationIfRunnable(configuration)
                        .stream())
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

    /** 确保生成模型已配置已达到可用状态。 */
    @Override
    public void ensureGenerationModelsConfigured() {
        requireRunnableModelByType(CHAT_MODEL_TYPE);
        requireRunnableModelByType(REASONING_MODEL_TYPE);
    }
}
