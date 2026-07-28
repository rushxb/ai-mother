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
        Function<String, CodeGenTypeEnum> routingFunction,
        String memoryContext,
        Supplier<String> deferredMemoryContextSupplier,
        String taskId
) {

    /**
 * 根据当前上下文解析记忆上下文。
 *
 * @return 处理后的记忆上下文文本
 */
    public String resolveMemoryContext() {
        return deferredMemoryContextSupplier == null
                ? memoryContext
                : deferredMemoryContextSupplier.get();
    }

    public GenerationOrchestrationRequest(
            App app,
            String userMessage,
            CodeGenTypeEnum currentType,
            String generatingStage,
            boolean hasGeneratedCode,
            Function<String, CodeGenTypeEnum> routingFunction,
            String memoryContext,
            String taskId
    ) {
        this(app, userMessage, currentType, generatingStage, hasGeneratedCode,
                routingFunction, memoryContext, null, taskId);
    }

    /** 仍然将身份创建委托给任务存储的调用者的兼容性构造函数。 */
    public GenerationOrchestrationRequest(
            App app,
            String userMessage,
            CodeGenTypeEnum currentType,
            String generatingStage,
            boolean hasGeneratedCode,
            Function<String, CodeGenTypeEnum> routingFunction,
            String memoryContext
    ) {
        this(app, userMessage, currentType, generatingStage, hasGeneratedCode,
                routingFunction, memoryContext, null, null);
    }
}
