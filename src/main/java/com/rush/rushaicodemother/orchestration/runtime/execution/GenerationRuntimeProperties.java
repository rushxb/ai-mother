package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumMap;

/**
 * 模型、工具、构建和修复循环共享的任务范围固定执行控制。
 */
@Data
@Component
@Validated
public class GenerationRuntimeProperties {

    public static final Duration TASK_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration MODEL_CALL_TIMEOUT = Duration.ofMinutes(4);
    public static final Duration MINIMUM_OPERATION_TIMEOUT = Duration.ofMillis(500);
    public static final Duration FIRST_PREVIEW_COMPLETION_RESERVE = Duration.ofSeconds(10);
    public static final Duration STREAM_SNAPSHOT_UPDATE_INTERVAL = Duration.ofSeconds(5);
    public static final int STREAM_SNAPSHOT_MAX_CHARS = 20_000;
    public static final int MAX_ROOT_MODEL_ATTEMPTS = 3;
    public static final int MAX_MODEL_TURNS = 16;
    public static final int MAX_PROVIDER_FAILOVER_ATTEMPTS = 4;
    public static final int MAX_TOOL_WRITES = 80;
    public static final int MAX_BUILD_EXECUTIONS = 2;
    public static final int MAX_REPAIR_ROUNDS = 1;

    private static final Duration MIN_STREAM_SNAPSHOT_UPDATE_INTERVAL = Duration.ofMillis(100);
    private static final Duration MAX_STREAM_SNAPSHOT_UPDATE_INTERVAL = Duration.ofMinutes(1);

    private Duration taskTimeout = TASK_TIMEOUT;

    private Duration modelCallTimeout = MODEL_CALL_TIMEOUT;

    private Duration minimumOperationTimeout = MINIMUM_OPERATION_TIMEOUT;

    private Duration firstPreviewCompletionReserve = FIRST_PREVIEW_COMPLETION_RESERVE;

    private Duration streamSnapshotUpdateInterval = STREAM_SNAPSHOT_UPDATE_INTERVAL;

    @Min(1)
    @Max(100_000)
    private int streamSnapshotMaxChars = STREAM_SNAPSHOT_MAX_CHARS;

    @Min(1)
    @Max(10)
    private int maxRootModelAttempts = MAX_ROOT_MODEL_ATTEMPTS;

    @Min(1)
    @Max(100)
    private int maxModelTurns = MAX_MODEL_TURNS;

    @Min(1)
    @Max(100)
    private int maxProviderFailoverAttempts = MAX_PROVIDER_FAILOVER_ATTEMPTS;

    @Min(1)
    @Max(500)
    private int maxToolWrites = MAX_TOOL_WRITES;

    @Min(1)
    @Max(20)
    private int maxBuildExecutions = MAX_BUILD_EXECUTIONS;

    @Min(1)
    @Max(10)
    private int maxRepairRounds = MAX_REPAIR_ROUNDS;

    /**
 * 将当前对象转换为限制。
 *
 * @return 限制
 */
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

    /**
 * 校验各时长配置及其相互约束是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
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
