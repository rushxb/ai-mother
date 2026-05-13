package com.yupi.yuaicodemother.service;

import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;

public interface GenerationMemoryContextService {

    String buildGenerationMemoryContext(App app, String userMessage, CodeGenTypeEnum targetType);

    String buildAutoRepairMemoryContext(Long appId, String taskId, String errorMessage, int repairRound);
}
