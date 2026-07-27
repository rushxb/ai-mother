package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
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
 * 模型、工具、构建和修复循环共享的任务范围执行控制。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-runtime")
public class GenerationRuntimeProperties {

    private static final Duration MIN_STREAM_SNAPSHOT_UPDATE_INTERVAL = Duration.ofMillis(100);
    private static final Duration MAX_STREAM_SNAPSHOT_UPDATE_INTERVAL = Duration.ofMinutes(1);

    private Duration taskTimeout = Duration.ofMinutes(10);

    private Duration modelCallTimeout = Duration.ofMinutes(4);

    private Duration minimumOperationTimeout = Duration.ofMillis(500);

    private Duration firstPreviewCompletionReserve = Duration.ofSeconds(10);

    private Duration streamSnapshotUpdateInterval = Duration.ofSeconds(5);

    @Min(1)
    @Max(100_000)
    private int streamSnapshotMaxChars = 20_000;

    @Min(1)
    @Max(10)
    private int maxRootModelAttempts = 3;

    @Min(1)
    @Max(100)
    private int maxModelTurns = 16;

    @Min(1)
    @Max(100)
    private int maxProviderFailoverAttempts = 4;

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
        if (!isModelBudgetConfigurationValid()) {
            throw new IllegalArgumentException("根模型调用预算无法覆盖重型生成与修复流程");
        }
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, maxRootModelAttempts);
        budgets.put(GenerationBudgetKind.MODEL_TURN, maxModelTurns);
        budgets.put(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT, maxProviderFailoverAttempts);
        budgets.put(GenerationBudgetKind.TOOL_WRITE, maxToolWrites);
        budgets.put(GenerationBudgetKind.BUILD_EXECUTION, maxBuildExecutions);
        budgets.put(GenerationBudgetKind.REPAIR_ROUND, maxRepairRounds);
        return new GenerationExecutionLimits(
                taskTimeout,
                modelCallTimeout,
                minimumOperationTimeout,
                firstPreviewCompletionReserve,
                budgets
        );
    }

    @AssertTrue(message = "生成任务运行时长配置无效")
    public boolean isDurationConfigurationValid() {
        if (!isPositive(taskTimeout)
                || !isPositive(modelCallTimeout)
                || !isPositive(minimumOperationTimeout)
                || !isPositive(firstPreviewCompletionReserve)
                || !isPositive(streamSnapshotUpdateInterval)) {
            return false;
        }
        return modelCallTimeout.compareTo(taskTimeout) <= 0
                && minimumOperationTimeout.compareTo(modelCallTimeout) < 0
                && minimumOperationTimeout.compareTo(taskTimeout) < 0
                && firstPreviewCompletionReserve.compareTo(
                taskTimeout.minus(minimumOperationTimeout)) <= 0
                && streamSnapshotUpdateInterval.compareTo(MIN_STREAM_SNAPSHOT_UPDATE_INTERVAL) >= 0
                && streamSnapshotUpdateInterval.compareTo(MAX_STREAM_SNAPSHOT_UPDATE_INTERVAL) <= 0;
    }

    @AssertTrue(message = "生成任务根模型调用预算无法覆盖重型生成与修复流程")
    public boolean isModelBudgetConfigurationValid() {
        return GenerationRootModelBudgetTopology.supports(
                GenerationMode.HEAVY_EXPERT,
                maxRootModelAttempts,
                maxRepairRounds
        );
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
