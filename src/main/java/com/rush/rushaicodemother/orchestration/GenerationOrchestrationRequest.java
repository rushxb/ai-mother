package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 应用生成编排请求。
 */
public record GenerationOrchestrationRequest(
        App app,
        String userMessage,
        CodeGenTypeEnum currentType,
        String generatingStage,
        boolean hasGeneratedCode,
        Supplier<String> projectContextSupplier,
        Function<String, CodeGenTypeEnum> routingFunction,
        String memoryContext
) {
}
