package com.rush.rushaicodemother.service.aimodel;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 默认读取持久化模型池；独立运行角色可以提供冻结快照。 */
@Component
public class DefaultAiModelEnabledConfigurationSource
        implements AiModelEnabledConfigurationSource {

    private final AiModelPersistenceService persistenceService;
    private final Optional<AiModelEnabledConfigurationSnapshot> snapshot;

    public DefaultAiModelEnabledConfigurationSource(
            AiModelPersistenceService persistenceService,
            Optional<AiModelEnabledConfigurationSnapshot> snapshot) {
        this.persistenceService = persistenceService;
        this.snapshot = snapshot;
    }

    /**
 * 查找匹配的启用。
 *
 * @param modelType 模型类型
 * @return 启用集合
 */
    @Override
    public List<AiModelConfiguration> findEnabled(String modelType) {
        if (snapshot.isEmpty()) {
            return persistenceService.findEnabled(modelType);
        }
        String normalizedType = modelType == null ? "" : modelType.trim();
        if (normalizedType.isEmpty()) {
            return snapshot.get().enabledModels();
        }
        List<AiModelConfiguration> models = snapshot.get().enabledModels();
        return models.stream()
                .filter(model -> normalizedType.equals(model.getModelType()))
                .toList();
    }
}
