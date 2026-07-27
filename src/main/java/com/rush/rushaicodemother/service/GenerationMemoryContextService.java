package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * 生成记忆上下文服务契约。
 */
public interface GenerationMemoryContextService {

    String buildGenerationMemoryContext(App app, String userMessage, CodeGenTypeEnum targetType);

    String buildAutoRepairMemoryContext(Long appId, String taskId, String errorMessage, int repairRound);
}
