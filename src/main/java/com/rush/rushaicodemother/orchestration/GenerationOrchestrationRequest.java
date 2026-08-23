package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;

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
        String memoryContext,
        Supplier<String> deferredMemoryContextSupplier,
        String taskId,
        GenerationPlanningVariant planningVariant,
        GenerationScenarioDecision scenarioDecision
) {

    public GenerationOrchestrationRequest {
        if (scenarioDecision == null) {
            throw new IllegalArgumentException("编排请求必须携带冻结场景决策");
        }
        if (planningVariant == null) {
            planningVariant = GenerationPlanningVariant.CURRENT_DAG;
        }
    }

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

    /**
     * 使用准入阶段已经冻结的场景事实创建编排请求。
     *
     * <p>生产 Heavy 链路禁止在这里重新解析 Prompt 或再次调用类型路由器；
     * 目标工程类型、路由和验证下限都必须来自同一份不可变场景决策。</p>
     */
    public static GenerationOrchestrationRequest fromFrozenScenario(
            App app,
            String userMessage,
            CodeGenTypeEnum currentType,
            String generatingStage,
            boolean hasGeneratedCode,
            String memoryContext,
            Supplier<String> deferredMemoryContextSupplier,
            String taskId,
            GenerationPlanningVariant planningVariant,
            GenerationScenarioDecision scenarioDecision
    ) {
        if (scenarioDecision == null) {
            throw new IllegalArgumentException("冻结场景决策不能为空");
        }
        return new GenerationOrchestrationRequest(
                app,
                userMessage,
                currentType,
                generatingStage,
                hasGeneratedCode,
                memoryContext,
                deferredMemoryContextSupplier,
                taskId,
                planningVariant,
                scenarioDecision
        );
    }
}
