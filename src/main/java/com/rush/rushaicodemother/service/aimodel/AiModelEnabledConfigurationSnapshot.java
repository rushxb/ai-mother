package com.rush.rushaicodemother.service.aimodel;

import java.util.List;

/** 为需要冻结模型池的独立运行角色提供不可变快照。 */
public interface AiModelEnabledConfigurationSnapshot {

    List<AiModelConfiguration> enabledModels();
}
