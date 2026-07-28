package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/** 允许在昂贵的发电阶段之前使用的最小剩余时间窗口。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-stage-admission")
public class GenerationStageAdmissionProperties {

    /** 新模型回合至少需要具备的有效执行时间。 */
    private Duration modelTurnMinimum = Duration.ofSeconds(30);

    /** 模型阶段向构建阶段交接时保留的调度余量。 */
    private Duration modelHandoffReserve = Duration.ofSeconds(2);

    /** 可选自动修复回合的最小有用模型窗口。 */
    private Duration repairModelMinimum = Duration.ofSeconds(60);

    /** 用于依赖项准备和项目构建验证的最小有用窗口。 */
    private Duration buildMinimum = Duration.ofSeconds(45);

    /** 开发服务器启动和延迟错误收集的最小有用窗口。 */
    private Duration runtimeValidationMinimum = Duration.ofSeconds(15);

    /** 为发布、生命周期持久性、收费和终端事件提供时间保护。 */
    private Duration terminalizationReserve = Duration.ofSeconds(10);

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "generation stage admission durations must all be greater than zero")
    public boolean isConfigurationValid() {
        return Stream.of(
                        modelTurnMinimum,
                        modelHandoffReserve,
                        repairModelMinimum,
                        buildMinimum,
                        runtimeValidationMinimum,
                        terminalizationReserve
                )
                .allMatch(value -> value != null && !value.isZero() && !value.isNegative());
    }

    /** 计算模型回合结束后必须保护的构建、验证、收尾与调度窗口。 */
    public Duration modelCompletionReserve(CodeGenTypeEnum targetType) {
        Objects.requireNonNull(targetType, "目标工程类型不能为空");
        Duration downstreamReserve = switch (targetType) {
            case HTML, MULTI_FILE -> terminalizationReserve;
            case BACKEND_PROJECT -> terminalizationReserve.plus(buildMinimum);
            case VUE_PROJECT, FULL_STACK_PROJECT -> terminalizationReserve
                    .plus(buildMinimum)
                    .plus(runtimeValidationMinimum);
        };
        return downstreamReserve.plus(modelHandoffReserve);
    }

    /** 计算指定工程类型启动一个有效模型回合所需的最小剩余时间。 */
    public Duration modelTurnMinimumRequired(CodeGenTypeEnum targetType) {
        return modelTurnMinimum.plus(modelCompletionReserve(targetType));
    }

    /** 返回能够覆盖任意工程类型的模型回合最小剩余时间。 */
    public Duration maximumModelTurnMinimumRequired() {
        return Arrays.stream(CodeGenTypeEnum.values())
                .map(this::modelTurnMinimumRequired)
                .max(Duration::compareTo)
                .orElseThrow(() -> new IllegalStateException("未配置可用的工程类型"));
    }
}
