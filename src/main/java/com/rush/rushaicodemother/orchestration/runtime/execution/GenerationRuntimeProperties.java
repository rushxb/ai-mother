package com.rush.rushaicodemother.orchestration.runtime.execution;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumMap;

/**
 * Task-wide execution controls shared by models, tools, builds and repair loops.
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-runtime")
public class GenerationRuntimeProperties {

    private Duration taskTimeout = Duration.ofMinutes(10);

    private Duration modelCallTimeout = Duration.ofMinutes(4);

    private Duration minimumOperationTimeout = Duration.ofMillis(500);

    @Min(1)
    @Max(10)
    private int maxModelAttempts = 2;

    @Min(1)
    @Max(500)
    private int maxToolWrites = 80;

    @Min(1)
    @Max(20)
    private int maxBuildExecutions = 2;

    @Min(1)
    @Max(10)
    private int maxRepairRounds = 1;

    public GenerationExecutionLimits toLimits() {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        budgets.put(GenerationBudgetKind.MODEL_ATTEMPT, maxModelAttempts);
        budgets.put(GenerationBudgetKind.TOOL_WRITE, maxToolWrites);
        budgets.put(GenerationBudgetKind.BUILD_EXECUTION, maxBuildExecutions);
        budgets.put(GenerationBudgetKind.REPAIR_ROUND, maxRepairRounds);
        return new GenerationExecutionLimits(taskTimeout, modelCallTimeout, minimumOperationTimeout, budgets);
    }

    @AssertTrue(message = "生成任务运行时长配置无效")
    public boolean isDurationConfigurationValid() {
        if (!isPositive(taskTimeout) || !isPositive(modelCallTimeout) || !isPositive(minimumOperationTimeout)) {
            return false;
        }
        return modelCallTimeout.compareTo(taskTimeout) <= 0
                && minimumOperationTimeout.compareTo(modelCallTimeout) < 0;
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
