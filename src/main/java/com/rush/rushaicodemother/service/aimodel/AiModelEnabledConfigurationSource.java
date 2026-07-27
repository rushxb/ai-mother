package com.rush.rushaicodemother.service.aimodel;

import java.util.List;

/** 提供当前进程实际使用的已启用模型配置。 */
public interface AiModelEnabledConfigurationSource {

    List<AiModelConfiguration> findEnabled(String modelType);
}
